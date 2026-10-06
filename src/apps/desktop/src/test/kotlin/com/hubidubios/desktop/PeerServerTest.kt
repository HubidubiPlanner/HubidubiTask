package com.hubidubios.desktop

import com.hubidubios.core.EntityRecord
import com.hubidubios.core.peer.MemoryPeerStore
import com.hubidubios.core.peer.PairResponse
import com.hubidubios.core.peer.PairingClient
import com.hubidubios.core.peer.PeerJson
import com.hubidubios.core.peer.PeerPairing
import com.hubidubios.core.peer.PeerSyncer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Files
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Mac's real server over real TLS, with a JVM stand-in for the phone. */
class PeerServerTest {
    private val store = Store(Files.createTempDirectory("mac").toFile())
    private val server = PeerServer(store, "Test Mac") {}.also { it.start() }
    private val base = "https://127.0.0.1:${server.port}"
    private val json = "application/json".toMediaType()

    @AfterTest fun stop() = server.stop()

    private class Tm(val pin: String?) : X509TrustManager {
        var seen: String? = null
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>, a: String?) {
            seen = PeerPairing.sha256Hex(c[0].encoded)
            if (pin != null && seen != pin) throw java.security.cert.CertificateException("pin")
        }
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    private fun http(tm: Tm) = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory, tm)
        .hostnameVerifier { _, _ -> true }.build()

    private fun pair(code: String, fp: String): Pair<Int, String> {
        val req = PairingClient.request(code, "phone-1", "Pixel", fp)
        http(Tm(null)).newCall(Request.Builder().url("$base/peer/pair")
            .post(req.json().toRequestBody(json)).build()).execute().use { r ->
            val body = r.body!!.string()
            if (r.code == 200) assertTrue(PairingClient.verify(code, req, PairResponse.of(body), fp))
            return r.code to body
        }
    }

    @Test
    fun `pair, then sync both ways over TLS`() {
        // Learn the certificate the way the phone does.
        val probe = Tm(null)
        runCatching { http(probe).newCall(Request.Builder().url("$base/peer/hello").build()).execute().close() }
        val fp = probe.seen!!
        assertEquals(server.fingerprint, fp)

        assertEquals(409, pair("ABCD-EFGH", fp).first, "paired while the Mac wasn't waiting")
        server.desk.open("ABCD-EFGH")
        assertEquals(403, pair("ABCD-EFGH", "someone-else's-cert").first, "a middle-man passed")
        val (code, body) = pair("abcd efgh", fp)
        assertEquals(200, code)
        val token = PairResponse.of(body).token
        assertEquals("Pixel", store.peers().single().name)

        val phone = MemoryPeerStore()
        phone.put(EntityRecord("t1", "next_action", "2026-09-27T10:00:00Z", mapOf("text" to "from phone",
            "media_sha256" to PeerPairing.sha256Hex("photo".toByteArray()))))
        store.put(EntityRecord("t2", "next_action", "2026-09-27T10:00:01Z", mapOf("text" to "from mac")))
        val pinned = http(Tm(fp))
        val syncer = PeerSyncer(phone, peer = server.deviceId, self = "phone-1",
            send = { req ->
                pinned.newCall(Request.Builder().url("$base/peer/sync").header("Authorization", "Bearer $token")
                    .post(PeerJson.request(req).toRequestBody(json)).build()).execute().use {
                    assertEquals(200, it.code); PeerJson.response(it.body!!.string())
                }
            },
            blobsOf = { ch -> ch.mapNotNull { it.record.fields["media_sha256"] } },
            upload = { shas -> shas.forEach { sha ->
                pinned.newCall(Request.Builder().url("$base/peer/blob/$sha").header("Authorization", "Bearer $token")
                    .put("photo".toByteArray().toRequestBody()).build()).execute().close()
            } },
        )
        syncer.round()
        assertEquals("from phone", store.get("t1")!!.fields["text"])
        assertEquals("from mac", phone.get("t2")!!.fields["text"])
        assertEquals("photo", store.blob(PeerPairing.sha256Hex("photo".toByteArray()))!!.readText())

        // A later edit on the Mac beats an earlier one on the phone.
        store.put(EntityRecord("t1", "next_action", "2026-09-27T12:00:00Z", mapOf("text" to "mac later")))
        phone.put(EntityRecord("t1", "next_action", "2026-09-27T11:00:00Z", mapOf("text" to "phone earlier")))
        syncer.round()
        assertEquals("mac later", phone.get("t1")!!.fields["text"])
        assertEquals("mac later", store.get("t1")!!.fields["text"])

        // Without the token, nothing.
        pinned.newCall(Request.Builder().url("$base/peer/sync")
            .post(PeerJson.request(com.hubidubios.core.peer.SyncRequest("x", 0, emptyList())).toRequestBody(json))
            .build()).execute().use { assertEquals(401, it.code) }
    }
}
