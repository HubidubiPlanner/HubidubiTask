package com.hubidubitask.core.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * opencode's server ("opencode serve"), and Kilo's, which is the same API.
 * One question is one session: created, asked with a JSON schema so the
 * answer and the changes come back apart, then deleted, so no copy of the
 * items stays behind on that computer.
 */
class OpencodeAgent(private val config: AgentConfig) : HomeAgentClient {
    override val kind = AgentKind.of(config.kind).takeIf { it.structured } ?: AgentKind.OPENCODE

    private val base = config.endpoint.trim().trimEnd('/')
    private val auth = kind.authorization(config.apiKey)
    @Volatile private var certificate: String? = null
    private val probe = Agent.http(base, config.tlsFingerprint, 15, seen = { certificate = it })
    private val patient = Agent.http(base, config.tlsFingerprint, 600)

    private fun request(path: String) = Request.Builder().url(base + path)
        .apply { auth?.let { header("Authorization", it) } }

    private fun call(http: OkHttpClient, req: Request): JSONObject = try {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw AgentError("${kind.label} refused the password")
            if (!r.isSuccessful) throw AgentError(runCatching {
                JSONObject(text).let { it.optJSONObject("data")?.optString("message") ?: it.optString("message") }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "${kind.label} answered ${r.code}")
            // A delete answers a bare true; only an object is read.
            if (text.trimStart().startsWith("{")) JSONObject(text) else JSONObject()
        }
    } catch (e: AgentError) { throw e } catch (e: Exception) {
        throw AgentError("the agent could not be reached: ${e.message}", e)
    }

    private fun post(http: OkHttpClient, path: String, body: JSONObject) =
        call(http, request(path).post(body.toString().toRequestBody(JSON)).build())

    override fun turn(prompt: AgentPrompt): AgentReply {
        val session = post(probe, "/session", JSONObject().put("title", "HubidubiTask")).getString("id")
        try {
            return ask(session, prompt)
        } finally {
            forget(session)
        }
    }

    private fun forget(session: String) {
        try { call(probe, request("/session/$session").delete().build()) } catch (_: Exception) {}
    }

    private fun ask(session: String, prompt: AgentPrompt): AgentReply {
        val text = listOf(prompt.context, "Question:\n" + prompt.question)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val res = post(patient, "/session/$session/message", JSONObject()
            .put("system", prompt.instructions)
            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("format", JSONObject().put("type", "json_schema").put("schema", AgentActions.SCHEMA)))
        val info = res.optJSONObject("info") ?: JSONObject()
        info.optJSONObject("error")?.let { e ->
            throw AgentError(e.optJSONObject("data")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: e.optString("name").ifBlank { "${kind.label} could not answer" })
        }
        (structured(info) ?: structured(res))?.let { return it }
        val parts = res.optJSONArray("parts") ?: JSONArray()
        return AgentActions.fromText((0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") })
    }

    /** The schema-held answer, as an object or as its JSON text. */
    private fun structured(o: JSONObject): AgentReply? {
        val v = o.opt("structured_output") ?: o.opt("structuredOutput") ?: return null
        return when (v) {
            is JSONObject -> AgentActions.fromJson(v)
            is String -> runCatching { AgentActions.fromJson(JSONObject(v)) }.getOrNull()
            else -> null
        }
    }

    override fun health(): Health = try {
        probe.newCall(request("/global/health").build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) Health(false, kind.label, emptyList(),
                if (r.code == 401) "${kind.label} refused the password" else "answered ${r.code}")
            else {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
                Health(o.optBoolean("healthy", true), "${kind.label} ${o.optString("version")}".trim(),
                    emptyList(), fingerprint = certificate.takeIf { r.request.isHttps })
            }
        }
    } catch (e: Exception) {
        Health(false, kind.label, emptyList(), e.message)
    }

    private companion object { val JSON = "application/json".toMediaType() }
}
