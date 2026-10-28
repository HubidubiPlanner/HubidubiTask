package com.hubidubitask.core.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Any OpenAI-compatible server: POST {base}/v1/chat/completions, GET {base}/v1/models. */
class OpenAiClient(private val config: AgentConfig) : ModelClient {

    private val base = config.endpoint.trim().trimEnd('/')
        .removeSuffix("/chat/completions").trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
    @Volatile private var certificate: String? = null
    private val probe = Agent.http(base, config.tlsFingerprint, 15, seen = { certificate = it })
    private val patient = Agent.http(base, config.tlsFingerprint, 600)

    private fun Request.Builder.auth() = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    // With no model named, the first the server lists, asked for once.
    @Volatile private var listed: String? = null
    private fun model(): String = config.model.takeIf { it.isNotBlank() } ?: listed
        ?: health().models.firstOrNull { it.isNotBlank() }?.also { listed = it }
        ?: throw AgentError("the server lists no model")

    override fun complete(messages: List<Message>): String {
        val body = JSONObject().put("model", model()).put("temperature", 0.2)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", content(it))) }
            })
        val req = Request.Builder().url("$base/chat/completions").auth()
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            patient.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw AgentError(runCatching {
                    JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                    ?: "the agent answered ${r.code}")
                JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
            }
        } catch (e: AgentError) { throw e } catch (e: Exception) {
            throw AgentError("the agent could not be reached: ${e.message}", e)
        }
    }

    /** Plain text, or with images the parts the OpenAI shape takes: the text, then each image inline. */
    private fun content(m: Message): Any =
        if (m.images.isEmpty()) m.content
        else JSONArray().put(JSONObject().put("type", "text").put("text", m.content)).apply {
            m.images.forEach { p ->
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", p.dataUrl)))
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
