package com.hubidubios.mobile.net

import com.hubidubios.core.ChangeBatch
import com.hubidubios.core.OutboxEntry
import com.hubidubios.core.PushResult
import com.hubidubios.core.RemoteChange
import com.hubidubios.core.Transport
import com.hubidubios.mobile.data.Json
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport over pinned self-signed TLS (PLAN.md Phase 5). We pin the exact
 * cert fingerprint captured at pairing: a self-signed cert pinned this way is
 * strictly stronger than public-CA trust, and mitmproxy on the LAN cannot read
 * the traffic. A mismatched cert refuses the connection.
 */
class PinnedTransport(private val server: PairedServer) : Transport {

    private val json = "application/json".toMediaType()
    private val client: OkHttpClient = buildPinnedClient(server.certFingerprint)

    override fun pull(since: Long): ChangeBatch {
        val req = authed(Request.Builder().url("${server.baseUrl}/v1/changes?since=$since")).build()
        client.newCall(req).execute().use { resp ->
            val body = JSONObject(resp.body!!.string())
            val arr = body.getJSONArray("changes")
            val changes = ArrayList<RemoteChange>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val op = c.getString("op")
                val data = if (c.isNull("data")) null else c.getJSONObject("data")
                changes.add(
                    RemoteChange(
                        seq = c.getLong("seq"),
                        id = c.getString("id"),
                        type = c.getString("type"),
                        op = op,
                        updated = c.getString("updated"),
                        record = data?.let { Json.recordFromFeed(it) },
                    )
                )
            }
            return ChangeBatch(changes, body.getLong("cursor"), body.optBoolean("has_more", false))
        }
    }

    override fun push(entries: List<OutboxEntry>): PushResult {
        val arr = JSONArray()
        for (e in entries) {
            val r = e.record
            val o = JSONObject()
                .put("id", r.id).put("type", r.type).put("op", e.op)
                .put("updated", r.updated)
            if (e.op != "delete") o.put("data", JSONObject(Json.fieldsToJson(r.fields)))
            arr.put(o)
        }
        val payload = JSONObject().put("changes", arr).toString()
        val req = authed(
            Request.Builder().url("${server.baseUrl}/v1/changes")
                .post(payload.toRequestBody(json))
        ).build()
        client.newCall(req).execute().use { resp ->
            val body = JSONObject(resp.body!!.string())
            val results = HashMap<String, String>()
            val rs = body.getJSONArray("results")
            for (i in 0 until rs.length()) {
                val r = rs.getJSONObject(i)
                results[r.getString("id")] = r.getString("status")
            }
            return PushResult(results, body.optLong("cursor", 0))
        }
    }

    private val octet = "application/octet-stream".toMediaType()

    override fun uploadBlob(sha: String, bytes: ByteArray): Boolean {
        val req = authed(
            Request.Builder().url("${server.baseUrl}/v1/blobs/$sha")
                .put(bytes.toRequestBody(octet))
        ).build()
        client.newCall(req).execute().use { resp -> return resp.isSuccessful }
    }

    override fun downloadBlob(sha: String): ByteArray? {
        val req = authed(Request.Builder().url("${server.baseUrl}/v1/blobs/$sha")).build()
        client.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    private fun authed(b: Request.Builder) =
        b.header("Authorization", "Bearer ${server.deviceToken}")

    private fun buildPinnedClient(fingerprint: String): OkHttpClient {
        val expected = fingerprint.removePrefix("sha256:").lowercase()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                val leaf = chain[0]
                val sha = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                val hex = sha.joinToString("") { "%02x".format(it) }
                if (hex != expected) {
                    throw javax.net.ssl.SSLPeerUnverifiedException(
                        "cert fingerprint mismatch: refusing connection")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), java.security.SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            // Cert identity is the pin, not the hostname (self-signed LAN cert).
            .hostnameVerifier { _: String, _: SSLSession -> true }
            .build()
    }
}
