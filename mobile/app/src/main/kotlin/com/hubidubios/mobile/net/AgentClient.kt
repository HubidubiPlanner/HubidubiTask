package com.hubidubios.mobile.net

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

    // Two clients, because the two jobs have opposite deadlines. A status probe
    // must fail fast so the UI is not held up by a box that is off. Generating
    // an answer is slow by nature — the model may still be loading, and a large
    // one takes minutes before the first token — so OkHttp's 10s default read
    // timeout cancels the request long before the agent has said anything.
    private val client = pinnedClient(server.certFingerprint, readTimeoutSeconds = 15)
    private val patientClient =
        pinnedClient(server.certFingerprint, readTimeoutSeconds = 600)

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

    /** The inference settings the workstation holds. `apiKeySet` only reports
     *  whether a key exists — the workstation never sends the secret itself. */
    data class AgentConfig(
        val protocol: String, val endpoint: String,
        val model: String?, val tlsFingerprint: String?, val apiKeySet: Boolean,
    )

    fun config(): AgentConfig? {
        val req = Request.Builder()
            .url("${server.baseUrl}/v1/agent/config")
            .header("Authorization", "Bearer ${server.deviceToken}")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val o = JSONObject(resp.body!!.string())
                fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
                AgentConfig(
                    protocol = str("protocol") ?: "openai",
                    endpoint = str("endpoint") ?: "",
                    model = str("model"),
                    tlsFingerprint = str("tls_fingerprint"),
                    apiKeySet = o.optBoolean("api_key_set", false),
                )
            }
        } catch (e: Exception) { null }
    }

    /** Point the workstation's agent at an inference server.
     *  Returns null on success, or a message explaining the refusal. */
    fun setConfig(endpoint: String, protocol: String, model: String?): String? {
        val payload = JSONObject()
            .put("endpoint", endpoint)
            .put("protocol", protocol)
        // Always sent, blank included: an empty model means "use the
        // workstation's default", and omitting the key would instead leave a
        // previously-set override in place with no way to clear it.
        payload.put("model", model.orEmpty())
        val req = Request.Builder()
            .url("${server.baseUrl}/v1/agent/config")
            .header("Authorization", "Bearer ${server.deviceToken}")
            .post(payload.toString().toRequestBody(json)).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> null
                    // The sync server answers an unknown route with a bare
                    // "not found", which explains nothing. It means this
                    // workstation predates the setting, not that the server is
                    // unreachable or the address wrong.
                    resp.code == 404 ->
                        "This workstation is too old to set the agent server " +
                            "from the phone — update HubidubiOS on it."
                    resp.code == 401 -> "Not authorised — re-pair with the workstation."
                    else -> JSONObject(text).optString("error", "failed (${resp.code})")
                }
            }
        } catch (e: Exception) { e.message ?: "could not reach the workstation" }
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
            patientClient.newCall(req).execute().use { resp ->
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
        } catch (e: java.net.SocketTimeoutException) {
            "The agent did not answer in time. If the model was cold it may " +
                "still be loading — try again in a moment."
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun pinnedClient(fingerprint: String, readTimeoutSeconds: Long): OkHttpClient {
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
            // Connecting is either instant on a LAN or never; the wait that
            // matters is for the answer.
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)   // bounded by read
            .build()
    }
}
