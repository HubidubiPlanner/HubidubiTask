package com.prodos.mobile.net

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Phone-side client for the workstation's agent endpoints (Phase 8):
 * - GET  /v1/agent/health  → inference-connection status for the native chip.
 * - POST /v1/agent/ask     → streamed NDJSON agent turns (online-only chat).
 *
 * The phone cannot reach the loopback-only model directly; the workstation
 * probes and proxies. Same pinned self-signed TLS as PinnedTransport.
 */
class AgentClient(private val server: PairedServer) {

    private val json = "application/json".toMediaType()
    private val client = pinnedClient(server.certFingerprint)

    data class Health(val connected: Boolean, val server: String?, val models: List<String>)

    fun health(): Health {
        val req = Request.Builder()
            .url("${server.baseUrl}/v1/agent/health")
            .header("Authorization", "Bearer ${server.deviceToken}")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val o = JSONObject(resp.body!!.string())
                val models = o.optJSONArray("models")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList()
                Health(o.optBoolean("connected", false),
                       if (o.isNull("server")) null else o.optString("server"),
                       models)
            }
        } catch (e: Exception) {
            Health(false, null, emptyList())
        }
    }

    /** Blocking read of the NDJSON stream; returns the final answer text, or an
     *  error string. Chat is online-only, so callers gate on health() first. */
    fun ask(query: String): String {
        val body = JSONObject().put("query", query).toString().toRequestBody(json)
        val req = Request.Builder()
            .url("${server.baseUrl}/v1/agent/ask")
            .header("Authorization", "Bearer ${server.deviceToken}")
            .post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                var answer = ""
                resp.body!!.source().use { src ->
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        val e = JSONObject(line)
                        when (e.optString("event")) {
                            "answer" -> answer = e.optString("answer")
                            "error" -> return "error: ${e.optString("error")}"
                        }
                    }
                }
                answer.ifEmpty { "(no answer)" }
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun pinnedClient(fingerprint: String): OkHttpClient {
        val expected = fingerprint.removePrefix("sha256:").lowercase()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, a: String?) {
                val hex = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (hex != expected) throw javax.net.ssl.SSLPeerUnverifiedException("pin mismatch")
            }
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tm), java.security.SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
