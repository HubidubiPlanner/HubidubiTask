package com.hubidubitask.core.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Any OpenAI-compatible server: POST {base}/v1/chat/completions, GET {base}/v1/models.
 * For the Harness, [items] go with a question that may use tools, and the
 * changes it made come back to them.
 */
class OpenAiClient(private val config: AgentConfig, private val items: AgentItems? = null) : ModelClient {

    private val base = config.endpoint.trim().trimEnd('/')
        .removeSuffix("/chat/completions").trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
    @Volatile private var certificate: String? = null
    private val probe = Agent.http(config.tlsFingerprint, 15) { certificate = it }
    private val patient = Agent.http(config.tlsFingerprint, 600)

    private fun Request.Builder.auth() = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    override fun complete(messages: List<Message>): String {
        val body = JSONObject().put("model", config.model).put("temperature", 0.2)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        val harness = config.protocol == "harness"
        if (harness) {
            // The harness keeps every tool off unless a request asks; say it either way.
            body.put("hubidubitask_tools", config.tools)
            if (config.tools && items != null) body.put("hubidubitask_items", AgentLink.itemsJson(items.records()))
        }
        val req = Request.Builder().url("$base/chat/completions").auth()
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            patient.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw AgentError(runCatching {
                    JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                    ?: "the agent answered ${r.code}")
                val answer = JSONObject(text)
                if (harness && items != null)
                    AgentLink.actionsOf(answer).takeIf { it.isNotEmpty() }?.let(items::apply)
                answer.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
            }
        } catch (e: AgentError) { throw e } catch (e: Exception) {
            throw AgentError("the agent could not be reached: ${e.message}", e)
        }
    }

    override fun health(): Health = try {
        probe.newCall(Request.Builder().url("$base/models").auth().build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) return Health(false, base, emptyList(), runCatching {
                JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: "answered ${r.code}")
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            Health(true, base, (0 until data.length()).map { data.getJSONObject(it).optString("id") },
                fingerprint = certificate.takeIf { r.request.isHttps })
        }
    } catch (e: Exception) {
        Health(false, base, emptyList(), e.message)
    }
}
