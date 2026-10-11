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
 * remembered for pinning; plain HTTP only inside the home network.
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
            for (scheme in if (plainAllowed) listOf("https", "http") else listOf("https")) {
                val r = try { at(AgentLink.endpoint(scheme, host, p), token) } catch (e: Exception) {
                    // Nothing listening, or no route: this port is done. Anything else (a
                    // plain-HTTP server fails or ignores the TLS handshake): try it plainly.
                    if (e is ConnectException || e is NoRouteToHostException) break
                    continue
                }
                when (r) {
                    is Result.Found -> return r
                    is Result.NeedsToken -> if (best == Result.NotFound) best = r
                    Result.NotFound -> {}
                }
                break
            }
        }
        return best
    }

    private class Reply(val code: Int, val body: String) {
        val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
    }

    /** What listens at [base]: throws when nothing answers there at all. */
    private fun at(base: String, token: String?): Result {
        var cert: String? = null
        val http = Agent.http(null, 3, connectTimeoutSeconds = 3) { cert = it }
        fun get(path: String, auth: String? = null): Reply =
            http.newCall(Request.Builder().url(base + path).apply { auth?.let { header("Authorization", it) } }.build())
                .execute().use { Reply(it.code, it.body?.string().orEmpty()) }
        val pin = { if (base.startsWith("https")) cert else null }

        // opencode or Kilo: /global/health, with the password as Basic auth.
        get("/global/health").let { r ->
            if (r.code == 401 || r.json?.has("healthy") == true) {
                if (r.code != 401) return Result.Found(kindOf(base, null, http), base, "", pin())
                if (token.isNullOrBlank()) return Result.NeedsToken(AgentKind.OPENCODE, base, false)
                for (k in listOf(AgentKind.OPENCODE, AgentKind.KILO)) {
                    val auth = k.authorization(token)
                    if (get("/global/health", auth).code in 200..299)
                        return Result.Found(kindOf(base, auth, http).takeIf { it != AgentKind.OPENCODE } ?: k, base, "", pin())
                }
                return Result.NeedsToken(AgentKind.OPENCODE, base, true)
            }
        }
        // OpenClaw or Hermes: /health, then the token checked on /v1/models.
        val health = get("/health")
        val kind = health.json?.let { j ->
            when {
                j.optBoolean("ok", false) -> AgentKind.OPENCLAW
                j.optString("status") == "ok" -> AgentKind.HERMES
                else -> null
            }
        }.takeIf { health.code in 200..299 }
        val auth = (kind ?: AgentKind.OPENAI).authorization(token)
        val models = get("/v1/models", auth)
        if (models.code == 401 || models.code == 403)
            return Result.NeedsToken(kind ?: AgentKind.OPENAI, base, !token.isNullOrBlank())
        if (models.code in 200..299) {
            val ids = models.json?.optJSONArray("data")?.let { d ->
                (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            val model = when (kind) {
                AgentKind.OPENCLAW -> ids.firstOrNull { it == "openclaw" } ?: "openclaw"
                AgentKind.HERMES -> ids.firstOrNull() ?: "hermes-agent"
                else -> ids.firstOrNull() ?: return Result.NotFound
            }
            return Result.Found(kind ?: AgentKind.OPENAI, base, model, pin())
        }
        // OpenClaw keeps its chat endpoint off unless turned on: it is still the one
        // found, and the check after saving says what it answered.
        if (kind != null) return Result.Found(kind, base, if (kind == AgentKind.OPENCLAW) "openclaw" else "hermes-agent", pin())
        return Result.NotFound
    }

    /** Kilo answers /indexing/status; opencode does not. */
    private fun kindOf(base: String, auth: String?, http: OkHttpClient): AgentKind = runCatching {
        http.newCall(Request.Builder().url("$base/indexing/status").apply { auth?.let { header("Authorization", it) } }.build())
            .execute().use { if (it.code in 200..299) AgentKind.KILO else AgentKind.OPENCODE }
    }.getOrDefault(AgentKind.OPENCODE)
}
