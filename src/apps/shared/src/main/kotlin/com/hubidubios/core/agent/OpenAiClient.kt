package com.hubidubios.core.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Any OpenAI-compatible server: POST {base}/v1/chat/completions, GET {base}/v1/models. */
class OpenAiClient(private val config: AgentConfig) : ModelClient {

    private val base = config.endpoint.trim().trimEnd('/')
        .removeSuffix("/chat/completions").trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
    private val probe = Agent.http(config.tlsFingerprint, 15)
    private val patient = Agent.http(config.tlsFingerprint, 600)

    private fun Request.Builder.auth() = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    override fun complete(messages: List<Message>): String {
        val body = JSONObject().put("model", config.model).put("temperature", 0.2)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        val req = Request.Builder().url("$base/chat/completions").auth()
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            patient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw AgentError("the agent answered ${r.code}")
                JSONObject(r.body!!.string()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
            }
        } catch (e: AgentError) { throw e } catch (e: Exception) {
            throw AgentError("the agent could not be reached: ${e.message}", e)
        }
    }

    override fun health(): Health = try {
        probe.newCall(Request.Builder().url("$base/models").auth().build()).execute().use { r ->
            if (!r.isSuccessful) return Health(false, base, emptyList(), "answered ${r.code}")
            val data = JSONObject(r.body!!.string()).optJSONArray("data") ?: JSONArray()
            Health(true, base, (0 until data.length()).map { data.getJSONObject(it).optString("id") })
        }
    } catch (e: Exception) {
        Health(false, base, emptyList(), e.message)
    }
}
