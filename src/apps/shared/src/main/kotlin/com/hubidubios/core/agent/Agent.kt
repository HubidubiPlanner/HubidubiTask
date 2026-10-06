package com.hubidubios.core.agent

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Where the agent lives and how to speak to it. Held by each app itself — the
 * phone and the computer ask the agent directly, with nothing in between.
 *
 * [protocol] "claude" is Anthropic's Claude API, reached with an API key and
 * no endpoint of ours; "selfhostedai" is selfhostedai-agent's encrypted API (usually
 * :8443); "openai" is any OpenAI-compatible server; "harness" is a local
 * agent harness (Claude Code, run by src/harness/ on the user's own machine)
 * served in the OpenAI shape with a bearer token. [tlsFingerprint], when set,
 * pins a self-signed certificate (sha256 hex of the DER).
 */
data class AgentConfig(
    val endpoint: String,
    val protocol: String = "selfhostedai",
    val model: String = defaultModel(protocol),
    val apiKey: String? = null,
    val tlsFingerprint: String? = null,
    /** "auto" answers in the material's language; "en" / "pl" force one. */
    val language: String = "auto",
    /**
     * Whether the harness may use its tools (vault files, vault skills, web
     * pages, subagents) for this agent. Off for feeds: their text comes from
     * strangers' web pages. Only the harness has tools; others ignore this.
     */
    val tools: Boolean = true,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://192.168.1.50:8443"
        const val CLAUDE_MODEL = "claude-opus-5-5"

        /** Every agent the apps can use, in the order Settings offers them. */
        val PROTOCOLS = listOf("claude" to "Claude", "selfhostedai" to "SelfHostedAI",
            "openai" to "Model", "harness" to "Harness")

        /** The agents a feed may use: the two that write good one-line tasks. */
        val FEED_MODELS = listOf("claude" to "Claude", "harness" to "Harness")

        const val HARNESS_MODEL = "claude-code"

        fun defaultModel(protocol: String) = when (protocol) {
            "claude" -> CLAUDE_MODEL
            "harness" -> HARNESS_MODEL
            "selfhostedai" -> "fast"
            else -> "local"
        }
    }
}

data class Health(val connected: Boolean, val server: String?, val models: List<String>,
                  val error: String? = null)

data class Message(val role: String, val content: String)

class AgentError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A model the apps can ask: one completion, plain text in and out. */
interface ModelClient {
    fun complete(messages: List<Message>): String
    fun health(): Health
    fun close() {}
}

object Agent {

    fun clientFor(config: AgentConfig): ModelClient = when (config.protocol) {
        "claude" -> ClaudeClient(config)
        // The harness speaks the OpenAI shape; only where it runs differs.
        "openai", "harness" -> OpenAiClient(config)
        else -> SelfhostedaiClient(config)
    }

    private val LANGUAGE = mapOf(
        "au" to "Answer in the same language as the material.",
        "en" to "Always answer in English, whatever language the material is in.",
        "pl" to "Zawsze odpowiadaj po polsku, niezaleznie od jezyka materialu.",
    )

    /**
     * The sentence that pins the reply language. "auto" by default: measured
     * against the live server, demanding one language of material in another
     * sent the model into half a minute of reasoning and no answer.
     */
    fun languageInstruction(language: String?): String =
        LANGUAGE[(language ?: "auto").lowercase().take(2)] ?: LANGUAGE.getValue("au")

    fun plainSystem(language: String?, tools: Boolean = false): String =
        if (tools) "You are the user's assistant. Look up what you need in their vault " +
            "with your tools before answering. Answer in plain prose. Be concise and concrete. " +
            "Do not use JSON or markdown fences. " + languageInstruction(language)
        else "You are the user's assistant. Answer in plain prose. Be concise and concrete. " +
            "Do not use JSON, markdown fences, or tool calls. " + languageInstruction(language)

    /** Only the harness has real tools; for the others "tool calls" are text to avoid. */
    fun usesTools(config: AgentConfig) = config.protocol == "harness" && config.tools

    /**
     * Ask for prose. One retry: a cold generation can spend its whole budget
     * reasoning and stop before answering, and asking again straight away
     * answered every time it was tried. Failures come back prefixed "error: "
     * so a caller can tell an explanation from an answer.
     */
    fun ask(client: ModelClient, config: AgentConfig, question: String,
            context: String? = null): String {
        val messages = buildList {
            add(Message("system", plainSystem(config.language, usesTools(config))))
            if (!context.isNullOrBlank()) add(Message("system", context))
            add(Message("user", question))
        }
        var raw = ""
        repeat(2) {
            raw = try {
                client.complete(messages)
            } catch (e: Exception) {
                return "error: ${e.message ?: "the agent could not be reached"}"
            }
            val answer = Text.stripReasoning(raw).trim()
            if (answer.isNotEmpty()) return answer
        }
        return "error: the model spent its whole reply reasoning and never answered, " +
            "twice (${raw.length} characters of it). Try a shorter question."
    }

    /** An HTTP client for a LAN server with a self-signed certificate: any
     *  certificate is accepted unless [fingerprint] pins one. */
    internal fun http(fingerprint: String?, readTimeoutSeconds: Long): OkHttpClient {
        val pin = fingerprint?.removePrefix("sha256:")?.lowercase()?.replace(":", "")
            ?.takeIf { it.isNotBlank() }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (pin == null) return
                val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (got != pin) throw java.security.cert.CertificateException(
                    "the agent's certificate does not match the pinned fingerprint")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }   // a LAN IP has no name to verify
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }
}

/** Text clean-up shared by both transports, ported from hubidubios_agent. */
object Text {
    private val THINK = Regex("<(think|thinking|reasoning)>.*?</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /**
     * Drop a model's visible chain-of-thought. An unterminated block means the
     * model spent its whole budget thinking and never reached an answer; what
     * follows the opening tag is the monologue itself, so it goes too.
     */
    fun stripReasoning(text: String): String {
        val out = THINK.replace(text, "")
        val at = out.lowercase().indexOf("<think")
        return (if (at >= 0) out.substring(0, at) else out).trim()
    }

    private val ECHO = listOf("\nCurrent prompt:", "\nCurrent prompt/context:",
        "\nCurrent prompt/system instruction:")

    /**
     * Cut a reply where the model stops answering and starts reciting: the
     * persona template, the prompt itself coming back, or a second "Answer:"
     * to a question it made up.
     */
    fun trimEcho(text: String, prompt: String = ""): String {
        val cuts = ECHO.map { text.indexOf(it) }.filter { it >= 0 }.toMutableList()
        prompt.lines().map { it.trim() }.firstOrNull { it.length >= 40 }?.let { first ->
            text.indexOf(first, 1).takeIf { it >= 0 }?.let(cuts::add)
        }
        text.indexOf("\nAnswer:", 1).takeIf { it >= 0 }?.let(cuts::add)
        val cut = cuts.minOrNull()
        return (if (cut != null) text.substring(0, cut) else text).trim()
    }

    /**
     * One prompt string that fits, ending in a cue. The server keeps the
     * *front* of an over-long prompt, so the conversation is kept whole and the
     * system instructions are trimmed to what budget is left — the question is
     * the part that must survive.
     */
    fun flatten(messages: List<Message>, budget: Int = 3200): String {
        val cue = "\n\nAnswer:"
        val body = messages.filter { it.role != "system" }.map { it.content }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        var instructions = messages.filter { it.role == "system" }.map { it.content }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        val room = budget - body.length - cue.length
        if (room <= 0) return body.takeLast(budget - cue.length) + cue
        if (instructions.length > room)
            instructions = instructions.take(maxOf(0, room - 14)).trimEnd() + "\n[…]"
        return listOf(instructions, body).filter { it.isNotEmpty() }.joinToString("\n\n") + cue
    }
}
