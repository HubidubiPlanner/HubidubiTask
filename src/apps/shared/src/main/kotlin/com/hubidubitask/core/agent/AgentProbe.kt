package com.hubidubitask.core.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException

/**
 * Which of the user's agents listens at an address, found from the address
 * alone: each answers on a path of its own (opencode and Kilo /global/health,
 * OpenClaw and Hermes /health, any OpenAI-shaped server /v1/models). Without
 * a port, each kind's usual one is tried. HTTPS first, with its certificate
 * remembered for pinning; plain HTTP only inside the home network, and never
 * on a port where a TLS handshake went through. The token is sent only once
 * the agent and its scheme are known without it, and over TLS only to the
 * certificate first seen there.
 */
object AgentProbe {

    sealed interface Result {
        /** Found and answering with the token given (or needing none). */
        data class Found(val kind: AgentKind, val endpoint: String, val model: String,
                         val fingerprint: String?) : Result {
            val label get() = kind.label.ifEmpty { model.ifEmpty { endpoint } }
        }
        /** Found, but it wants a token ([refused]: the one given is wrong). */
        data class NeedsToken(val kind: AgentKind, val endpoint: String, val refused: Boolean) : Result {
            val label get() = kind.label.ifEmpty { endpoint }
        }
        data object NotFound : Result
    }

    fun detect(host: String, port: Int?, token: String?,
               ports: List<Int> = AgentKind.DEFAULT_PORTS,
               plainAllowed: Boolean = AgentLink.isPrivate(host)): Result {
        var best: Result = Result.NotFound
        for (p in port?.let(::listOf) ?: ports) {
            when (val r = on(host, p, token, plainAllowed)) {
                is Result.Found -> return r
                is Result.NeedsToken -> if (best == Result.NotFound) best = r
                Result.NotFound -> {}
            }
        }
        return best
    }

    /** What listens on one port: HTTPS first, plain HTTP only if no TLS handshake went through. */
    private fun on(host: String, port: Int, token: String?, plainAllowed: Boolean): Result {
        val tls = Site(AgentLink.endpoint("https", host, port))
        try {
            return at(tls, token)
        } catch (e: Exception) {
            // Nothing listening, or no route: this port is done. A TLS server that
            // answered and then failed is never asked again in the clear.
            if (e is ConnectException || e is NoRouteToHostException || tls.cert != null || !plainAllowed)
                return Result.NotFound
        }
        // A plain-HTTP server fails or ignores the TLS handshake: ask it plainly.
        return try { at(Site(AgentLink.endpoint("http", host, port)), token) } catch (e: Exception) { Result.NotFound }
    }

    private class Reply(val code: Int, val body: String) {
        val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
        val ok get() = code in 200..299
        val refused get() = code == 401 || code == 403
    }

    /**
     * One address being asked. Over TLS, the first certificate it shows is the
     * only one trusted for the rest of the probe, so the token reaches no other.
     */
    private class Site(val base: String) {
        @Volatile var cert: String? = null
            private set
        private val first = Agent.http(base, null, 3, connectTimeoutSeconds = 3,
            seen = { if (cert == null) cert = it })
        private var pinned: OkHttpClient? = null

        private fun client(): OkHttpClient = pinned
            ?: cert?.let { Agent.http(base, it, 3, connectTimeoutSeconds = 3, pinOnly = true).also { c -> pinned = c } }
            ?: first

        fun get(path: String, auth: String? = null): Reply =
            client().newCall(Request.Builder().url(base + path).apply { auth?.let { header("Authorization", it) } }.build())
                .execute().use { Reply(it.code, it.body?.string().orEmpty()) }

        /** Kilo answers /indexing/status; opencode does not. */
        fun kindOf(auth: String?): AgentKind = runCatching {
            if (get("/indexing/status", auth).ok) AgentKind.KILO else AgentKind.OPENCODE
        }.getOrDefault(AgentKind.OPENCODE)
    }

    /**
     * What listens at [s]: throws when nothing answers there at all. Each path
     * is asked without the token first; the token only checks an agent found.
     */
    private fun at(s: Site, token: String?): Result {
        // opencode or Kilo: /global/health, with the password as Basic auth.
        val global = s.get("/global/health")
        if (global.code == 401 || global.json?.has("healthy") == true) {
            if (global.code != 401) return Result.Found(s.kindOf(null), s.base, "", s.cert)
            if (token.isNullOrBlank()) return Result.NeedsToken(AgentKind.OPENCODE, s.base, false)
            for (k in listOf(AgentKind.OPENCODE, AgentKind.KILO)) {
                val auth = k.authorization(token)
                if (s.get("/global/health", auth).ok)
                    return Result.Found(s.kindOf(auth).takeIf { it != AgentKind.OPENCODE } ?: k, s.base, "", s.cert)
            }
            return Result.NeedsToken(AgentKind.OPENCODE, s.base, true)
        }
        // OpenClaw or Hermes: /health. Then /v1/models, with the token only if it asks for one.
        val health = s.get("/health")
        val kind = health.json?.let { j ->
            when {
                j.optBoolean("ok", false) -> AgentKind.OPENCLAW
                j.optString("status") == "ok" -> AgentKind.HERMES
                else -> null
            }
        }.takeIf { health.ok }
            // Any other kind by its own signature, still without the token.
            ?: AgentKind.entries.firstOrNull { k ->
                k.signature?.let { sig -> runCatching { s.get(sig.path) }.getOrNull()?.let { it.ok && sig.matches(it.json) } } == true
            }
        var models = s.get("/v1/models")
        if (models.refused) {
            val auth = (kind ?: AgentKind.OPENAI).authorization(token)
                ?: return Result.NeedsToken(kind ?: AgentKind.OPENAI, s.base, false)
            models = s.get("/v1/models", auth)
            if (models.refused) return Result.NeedsToken(kind ?: AgentKind.OPENAI, s.base, true)
        }
        if (models.ok) {
            val ids = models.json?.optJSONArray("data")?.let { d ->
                (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            val model = when (kind) {
                AgentKind.OPENCLAW -> ids.firstOrNull { it == "openclaw" } ?: "openclaw"
                AgentKind.HERMES -> ids.firstOrNull() ?: "hermes-agent"
                else -> ids.firstOrNull() ?: return Result.NotFound
            }
            return Result.Found(kind ?: AgentKind.OPENAI, s.base, model, s.cert)
        }
        // OpenClaw keeps its chat endpoint off unless turned on: it is still the one
        // found, and the check after saving says what it answered.
        if (kind != null) return Result.Found(kind, s.base, when (kind) {
            AgentKind.OPENCLAW -> "openclaw"
            AgentKind.HERMES -> "hermes-agent"
            else -> ""
        }, s.cert)
        return Result.NotFound
    }
}
