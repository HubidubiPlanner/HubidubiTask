package com.hubidubitask.core.agent

import com.hubidubitask.core.i18n.t
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Where the agent lives and how to speak to it. Held by each app itself — the
 * phone and the computer ask the agent directly, with nothing in between.
 *
 * [protocol] "claude" is Anthropic's Claude API, reached with an API key and
 * no endpoint of ours; "openai" is any OpenAI-compatible server; "harness" (labelled
 * "Agent"; the id stays because settings store it) is an agent the user
 * already runs on a computer at home, at the IP and port they typed, of the
 * [kind] found there (see [AgentKind], [AgentProbe]); [apiKey] is its token.
 * [tlsFingerprint], when set, pins the certificate of a server in the home
 * network (sha256 hex of the DER); a public one is held to the system's
 * certificate authorities instead (see [Agent.http]).
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
        const val CLAUDE_MODEL = "claude-opus-5-5"

        /** Every agent the apps can use, by id and name, in the order Settings offers them ([AgentTypes]). */
        val PROTOCOLS: List<Pair<String, String>> get() = AgentTypes.all.map { it.id to it.label }

        /** What a fresh install uses: the first choice in Settings, Claude. */
        const val DEFAULT_PROTOCOL = "claude"

        /** A stored choice the apps no longer offer reads as the default. */
        fun knownProtocol(id: String?): String = AgentTypes.of(id).id

        /**
         * What a feed writes its task with. Never the user's own agent: a feed's
         * text is a stranger's web page, and that agent can run commands.
         */
        val FEED_MODELS = listOf("claude" to "Claude")

        /** The model a type asks for; blank lets an OpenAI-shaped server answer with its first. */
        fun defaultModel(protocol: String) = AgentTypes.of(protocol).defaultModel
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

    fun clientFor(config: AgentConfig): ModelClient = AgentTypes.of(config.protocol).client(config)

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

    /**
     * An HTTP client for the agent at [endpoint], under one TLS policy for
     * every protocol. A public host gets standard TLS: the system's
     * certificate authorities and the host's name, any pin ignored. A host in
     * the home network ([isPrivate]) is trusted the same way, or else only by
     * [fingerprint] (sha256 hex of the leaf's DER) when one is pinned, or else
     * on first use. [seen] hears the sha256 of each certificate trusted there
     * (OkHttp's own handshake record drops a self-signed one it cannot chain
     * to a known root). [pinOnly] trusts the pinned certificate alone, even
     * over one an authority vouches for. Plain http:// goes only to a host in
     * the home network: anything else throws [AgentError] before connecting.
     */
    internal fun http(endpoint: String, fingerprint: String?, readTimeoutSeconds: Long,
                      connectTimeoutSeconds: Long = 10, seen: (String) -> Unit = {},
                      pinOnly: Boolean = false,
                      isPrivate: (String) -> Boolean = AgentLink::isPrivate): OkHttpClient {
        val trust = AgentTrust(endpoint.trim().toHttpUrlOrNull()?.host, fingerprint, pinOnly, seen, isPrivate)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        // Checked before connecting, and again for each redirect.
        val homeOnly = Interceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps && !isPrivate(url.host))
                throw AgentError(t("Plain HTTP is only for your home network. Use an https:// address."))
            chain.proceed(chain.request())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(trust)
            .addInterceptor(homeOnly)
            .addNetworkInterceptor(homeOnly)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    /** The sha256 of a certificate's DER, in hex: what a pin holds. */
    internal fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

/**
 * The TLS half of [Agent.http]: which certificates are trusted at [home].
 * The system's authorities first, with the host's name checked; then, only
 * at a host in the home network, the pin, or the first certificate when
 * there is none. Decided on each handshake: a name may resolve elsewhere
 * later.
 */
private class AgentTrust(
    private val home: String?, fingerprint: String?, private val pinOnly: Boolean,
    private val seen: (String) -> Unit, private val isPrivate: (String) -> Boolean,
) : X509TrustManager, HostnameVerifier {
    private val pin = fingerprint?.removePrefix("sha256:")?.lowercase()?.replace(":", "")
        ?.takeIf { it.isNotBlank() }
    /** Leaves an authority vouched for during a handshake, for [verify] after it. */
    private val vouched = ConcurrentHashMap.newKeySet<String>()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
        SYSTEM.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        try {
            SYSTEM.checkServerTrusted(chain, authType)
            vouched += Agent.fingerprint(chain[0])
        } catch (e: CertificateException) {
            // A public host has the system's answer; a home one, a pin it does not match.
            if (home == null || !isPrivate(home) || (pin != null && Agent.fingerprint(chain[0]) != pin)) throw e
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = SYSTEM.acceptedIssuers

    override fun verify(host: String, session: SSLSession): Boolean {
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            ?.let(Agent::fingerprint) ?: return false
        val named = leaf in vouched && OkHostnameVerifier.verify(host, session)
        // Anywhere but the home host typed, a redirect elsewhere too: standard TLS only.
        if (host != home || !isPrivate(host)) return named
        val trusted = (named && !pinOnly) || pin == null || leaf == pin
        if (trusted) seen(leaf)
        return trusted
    }

    private companion object {
        /** The system's certificate authorities. */
        val SYSTEM: X509TrustManager by lazy {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/** Text clean-up for what models answer. */
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
}
