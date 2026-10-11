package com.hubidubitask.core.agent

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.time.LocalDate
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
 * :8443); "openai" is any OpenAI-compatible server; "harness" (labelled
 * "Agent"; the id stays because settings store it) is an agent the user
 * already runs on a computer at home, at the IP and port they typed, of the
 * [kind] found there (see [AgentKind], [AgentProbe]); [apiKey] is its token.
 * [tlsFingerprint], when set, pins a self-signed certificate (sha256 hex of
 * the DER).
 */
data class AgentConfig(
    val endpoint: String,
    val protocol: String = DEFAULT_PROTOCOL,
    val model: String = defaultModel(protocol),
    val apiKey: String? = null,
    val tlsFingerprint: String? = null,
    /** "auto" answers in the material's language; "en" / "pl" force one. */
    val language: String = "auto",
    /** For the user's own agent: which one listens there ([AgentKind] id). */
    val kind: String? = null,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://192.168.1.50:8443"
        const val CLAUDE_MODEL = "claude-opus-5-5"

        /** Every agent the apps can use, in the order Settings offers them. */
        /** Labels only: the ids are what settings store, so they never change. "Agent" is the user's own. */
        val PROTOCOLS = listOf("selfhostedai" to "SelfHostedAI", "openai" to "OpenAI",
            "claude" to "Claude", "harness" to "Agent")

        /** What a fresh install uses: the first choice in Settings. */
        const val DEFAULT_PROTOCOL = "selfhostedai"

        /**
         * What a feed writes its task with. Never the user's own agent: a feed's
         * text is a stranger's web page, and that agent can run commands.
         */
        val FEED_MODELS = listOf("claude" to "Claude")

        fun defaultModel(protocol: String) = when (protocol) {
            "claude" -> CLAUDE_MODEL
            "harness" -> ""
            "selfhostedai" -> "fast"
            else -> "local"
        }
    }
}

data class Health(val connected: Boolean, val server: String?, val models: List<String>,
                  val error: String? = null,
                  /** The sha256 of the server's certificate, when it was reached over TLS. */
                  val fingerprint: String? = null)

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
        "openai" -> OpenAiClient(config)
        "harness" -> HomeAgent.clientFor(config)
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

    fun plainSystem(language: String?): String =
        "You are the user's assistant. Answer in plain prose. Be concise and concrete. " +
            "Do not use JSON, markdown fences, or tool calls. " + languageInstruction(language)

    /**
     * Ask for prose. One retry: a cold generation can spend its whole budget
     * reasoning and stop before answering, and asking again straight away
     * answered every time it was tried. Failures come back prefixed "error: "
     * so a caller can tell an explanation from an answer.
     *
     * The user's own agent also gets [items] to look at, and the changes it
     * asks for are checked and applied to them, once: a reply that changed
     * something is never asked for again.
     */
    fun ask(client: ModelClient, config: AgentConfig, question: String,
            items: AgentItems? = null, today: LocalDate = LocalDate.now()): String {
        if (client is HomeAgentClient) return askHome(client, config, question, items, today)
        val messages = listOf(Message("system", plainSystem(config.language)),
            Message("user", question))
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
            "twice (${raw.length} characters of it). Try again."
    }

    private fun askHome(client: HomeAgentClient, config: AgentConfig, question: String,
                        items: AgentItems?, today: LocalDate): String {
        val records = items?.records().orEmpty()
        val prompt = AgentPrompt(AgentActions.instructions(config.language, client.kind.structured),
            ItemsContext.render(records, today), question)
        var raw = ""
        repeat(2) {
            val reply = try { client.turn(prompt) } catch (e: Exception) {
                return "error: ${e.message ?: "the agent could not be reached"}"
            }
            val changes = AgentActions.validate(reply.actions, records)
            if (changes.isNotEmpty()) items?.apply(changes)
            val answer = Text.stripReasoning(reply.text).trim()
            if (answer.isNotEmpty()) return answer
            if (reply.actions.isNotEmpty()) return AgentActions.summary(changes, records)
                .ifEmpty { "error: none of the agent's changes fit an item here" }
            raw = reply.text
        }
        return "error: the agent spent its whole reply reasoning and never answered, " +
            "twice (${raw.length} characters of it). Try again."
    }

    /** An HTTP client for a LAN server with a self-signed certificate: any
     *  certificate is accepted unless [fingerprint] pins one. [seen] hears the
     *  sha256 of each certificate the server shows (OkHttp's own handshake
     *  record drops a self-signed one it cannot chain to a known root). */
    internal fun http(fingerprint: String?, readTimeoutSeconds: Long, connectTimeoutSeconds: Long = 10,
                      seen: (String) -> Unit = {}): OkHttpClient {
        val pin = fingerprint?.removePrefix("sha256:")?.lowercase()?.replace(":", "")
            ?.takeIf { it.isNotBlank() }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                seen(got)
                if (pin != null && got != pin) throw java.security.cert.CertificateException(
                    "the agent's certificate does not match the pinned fingerprint")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }   // a LAN IP has no name to verify
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }
}

/** Text clean-up shared by both transports, ported from hubidubitask_agent. */
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
