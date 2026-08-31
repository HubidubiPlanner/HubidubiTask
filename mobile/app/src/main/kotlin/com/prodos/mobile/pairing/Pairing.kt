package com.prodos.mobile.pairing

import com.prodos.mobile.net.PairedServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Pairing (PLAN.md Phase 5). The QR payload is
 * {host, port, cert_fingerprint, one_time_token}; we POST the one-time token to
 * /v1/pair (pinning the advertised fingerprint from the very first connection)
 * and receive a long-lived device token. No password is ever typed.
 */
object Pairing {

    data class QrPayload(
        val host: String, val port: Int,
        val certFingerprint: String, val oneTimeToken: String,
    )

    fun parseQr(text: String): QrPayload {
        val o = JSONObject(text)
        return QrPayload(
            host = o.getString("host"),
            port = o.getInt("port"),
            certFingerprint = o.getString("cert_fingerprint"),
            oneTimeToken = o.getString("one_time_token"),
        )
    }

    /** Redeem the one-time token. The connection pins the QR's fingerprint, so
     *  a MITM presenting a different cert is refused before the token is sent. */
    fun redeem(payload: QrPayload, deviceName: String): PairedServer {
        val client = pinnedClient(payload.certFingerprint)
        val body = JSONObject()
            .put("token", payload.oneTimeToken)
            .put("device_name", deviceName)
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://${payload.host}:${payload.port}/v1/pair")
            .post(body).build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body!!.string())
            if (!json.has("device_token")) {
                throw IllegalStateException("pairing rejected: ${json.optString("error")}")
            }
            return PairedServer(
                host = payload.host,
                port = payload.port,
                certFingerprint = payload.certFingerprint,
                deviceToken = json.getString("device_token"),
            )
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
