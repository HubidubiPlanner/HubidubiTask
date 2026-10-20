package com.hubidubitask.desktop

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.peer.Cursor
import com.hubidubitask.core.peer.MemoryPeerStore
import com.hubidubitask.core.peer.PeerAddress
import com.hubidubitask.core.peer.PeerClient
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.core.peer.PeerSession
import com.hubidubitask.core.peer.PhonePairing
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.sql.DriverManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The computer's real server over real TLS, with the phone's real client (shared) against it. */
class PeerServerTest {
    private val dir = Files.createTempDirectory("mac").toFile()
    private val store = Store(dir)
    private val keys = MemoryPeerKeys()
    private val servers = mutableListOf<PeerServer>()
    private val server = serve(store, keys)
    private val phoneBlobs = Files.createTempDirectory("phone-blobs").toFile()

    private fun serve(s: Store, k: PeerKeys, admit: (java.net.InetAddress) -> Boolean = { true }) =
        PeerServer(s, "Test Mac", k, listenPort = 0, admit = admit, announce = false) {}.also { it.start(); servers += it }

    @AfterTest fun stop() = servers.forEach { it.stop() }

    private fun client(s: PeerServer = server, pin: String? = null) = PeerClient("127.0.0.1", s.port, pin)

    private fun pair(code: String = "ABCD-EFGH"): PeerClient.PairOutcome.Paired {
        server.desk.open(code)
        return assertIs(client().pair(code.lowercase().replace("-", " "), "phone-1", "Pixel"))
    }

    /** A plain HTTPS client that accepts the test server's certificate. */
    private val raw: OkHttpClient = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>, a: String?) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }.let { tm ->
        OkHttpClient.Builder().sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }.retryOnConnectionFailure(false).build()
    }

    private fun post(path: String, body: ByteArray, vararg headers: Pair<String, String>, s: PeerServer = server): Int =
        raw.newCall(Request.Builder().url("https://127.0.0.1:${s.port}$path")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { it.code }

    @Test
    fun `pair, then sync both ways with photos larger than one chunk`() {
        assertEquals(false, client().pairingOpen())
        assertEquals(PeerClient.PairOutcome.NotReady, client().pair("ABCD-EFGH", "phone-1", "Pixel"))

        val paired = pair()
        assertEquals(server.fingerprint, paired.fingerprint)
        assertEquals(server.deviceId, paired.computerId)
        assertEquals("Pixel", store.peers().single().name)
        assertContentEquals(keys.get("phone-1"), paired.key)
        assertEquals(false, client().pairingOpen(), "the code is used up")

        // A photo on the phone (3 chunks) and a recording on the computer (2 chunks).
        val photo = Random.nextBytes(2 * PeerClient.CHUNK + 1234)
        val photoSha = PeerPairing.sha256Hex(photo)
        File(phoneBlobs, photoSha).writeBytes(photo)
        val memo = Random.nextBytes(PeerClient.CHUNK + 7)
        val memoSha = store.putBlob(memo)

        val phone = MemoryPeerStore()
        phone.put(EntityRecord("t1", "next_action", "2026-09-27T10:00:00Z",
            mapOf("text" to "from phone", "media_sha256" to photoSha)))
        store.put(EntityRecord("n2", "note", "2026-09-27T10:00:01Z",
            mapOf("text" to "from mac", "media_sha256" to memoSha, "media_mime" to "audio/mp4")))

        val fetched = mutableListOf<PeerClient.Fetched>()
        val c = client(pin = paired.fingerprint)
        val round = c.round(phone, "phone-1", paired.computerId, paired.key, phoneBlobs) { fetched += it }
        assertEquals(1, round.sent); assertEquals(1, round.received)
        assertEquals("from phone", store.get("t1")!!.fields["text"])
        assertEquals("from mac", phone.get("n2")!!.fields["text"])
        assertContentEquals(photo, store.blob(photoSha)!!.readBytes())
        assertContentEquals(memo, File(phoneBlobs, memoSha).readBytes())
        assertEquals(listOf(memoSha to "n2"), fetched.map { it.sha to it.recordId })
        assertTrue(dir.walk().none { it.name.startsWith(".part-") } && phoneBlobs.walk().none { it.name.startsWith(".part-") })

        // A later edit on the computer beats an earlier one on the phone.
        store.put(EntityRecord("t1", "next_action", "2026-09-27T12:00:00Z", mapOf("text" to "mac later")))
        phone.put(EntityRecord("t1", "next_action", "2026-09-27T11:00:00Z", mapOf("text" to "phone earlier")))
        c.round(phone, "phone-1", paired.computerId, paired.key, phoneBlobs)
        assertEquals("mac later", phone.get("t1")!!.fields["text"])
        assertEquals("mac later", store.get("t1")!!.fields["text"])
    }

    @Test
    fun `wrong codes are refused and five close the code`() {
        server.desk.open("ABCD-EFGH")
        repeat(PeerPairing.MAX_ATTEMPTS) {
            Thread.sleep(600)   // inside the rate limit, as a real phone is
            assertEquals(PeerClient.PairOutcome.WrongCode, client().pair("ABCD-EFGJ", "phone-1", "Pixel"))
        }
        assertFalse(server.desk.isOpen())
        assertTrue(store.peers().isEmpty())
        assertNull(keys.get("phone-1"))
    }

    @Test
    fun `a pinned certificate that isn't the computer's reaches nothing`() {
        server.desk.open("ABCD-EFGH")
        assertEquals(PeerClient.PairOutcome.Unreachable,
            client(pin = "00".repeat(32)).pair("ABCD-EFGH", "phone-1", "Pixel"))
        assertTrue(server.desk.isOpen())
    }

    @Test
    fun `the phone gives up after five failed tries across every computer it finds`() {
        val other = serve(Store(Files.createTempDirectory("mac2").toFile()), MemoryPeerKeys())
        server.desk.open("ABCD-EFGH"); other.desk.open("ABCD-EFGK")
        val at = listOf(PeerAddress("a", "127.0.0.1", server.port), PeerAddress("b", "127.0.0.1", other.port))
        val run = PhonePairing("ABCD-EFGJ", "phone-1", "Pixel", shownHere = true, pause = { Thread.sleep(600) })
        assertEquals(PhonePairing.Result.GaveUp, run.run({ at }))
        // Neither computer saw five tries: the phone's own count stopped it.
        assertTrue(server.desk.isOpen() && other.desk.isOpen())
    }

    @Test
    fun `a code typed on the phone and refused ends the run at once`() {
        server.desk.open("ABCD-EFGH")
        val run = PhonePairing("WXYZ-2345", "phone-1", "Pixel", shownHere = false, pause = {})
        assertEquals(PhonePairing.Result.WrongCode, run.run({ listOf(PeerAddress("a", "127.0.0.1", server.port)) }))
    }

    @Test
    fun `an unknown, unpaired or forgotten phone gets nothing`() {
        assertFailsWith<PeerClient.NotPaired> { client().connect("phone-1", server.deviceId, ByteArray(32)) }
        val paired = pair()
        // The right phone with the wrong key.
        assertFailsWith<PeerClient.NotPaired> { client().connect("phone-1", server.deviceId, ByteArray(32)) }
        assertEquals(410, post("/peer/v2/call", ByteArray(64), "X-Peer" to "phone-1", "X-Session" to "00".repeat(16)))
        assertEquals(401, post("/peer/v2/call", ByteArray(64), "X-Peer" to "phone-9", "X-Session" to "00".repeat(16)))
        client().connect("phone-1", server.deviceId, paired.key)   // works
        server.forget("phone-1")
        assertNull(keys.get("phone-1"))
        assertFailsWith<PeerClient.NotPaired> { client().connect("phone-1", server.deviceId, paired.key) }
    }

    @Test
    fun `a recorded request played again is refused`() {
        val paired = pair()
        val opener = PeerSession.Opener(paired.key, "phone-1", server.deviceId)
        val answer = raw.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/peer/v2/hello")
            .header("X-Peer", "phone-1").post(opener.hello.toRequestBody()).build()).execute().use { it.body!!.bytes() }
        val session = assertNotNull(opener.open(answer))
        val req = """{"from":"phone-1","ack":0,"changes":[],"blobs":[]}""".toByteArray()
        val (_, body) = session.seal(JSONObject().put("op", "sync"), req)
        val h = arrayOf("X-Peer" to "phone-1", "X-Session" to session.sidHex)
        assertEquals(200, post("/peer/v2/call", body, *h))
        assertEquals(400, post("/peer/v2/call", body, *h))
    }

    @Test
    fun `bodies over the limit are refused, chunked ones too`() {
        server.desk.open("ABCD-EFGH")
        assertEquals(413, post("/peer/v2/pair/start", ByteArray(100_000)))
        val chunked = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun writeTo(sink: BufferedSink) { repeat(100) { sink.write(ByteArray(1000)) } }
        }
        raw.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/peer/v2/pair/start").post(chunked).build())
            .execute().use { assertEquals(413, it.code) }
    }

    @Test
    fun `a session the computer let go is opened again without losing the request`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val first = PeerServer(store, "Test Mac", keys, listenPort = port, announce = false) {}.also { it.start() }
        first.desk.open("ABCD-EFGH")
        val paired = assertIs<PeerClient.PairOutcome.Paired>(PeerClient("127.0.0.1", port, null).pair("ABCD-EFGH", "phone-1", "Pixel"))
        val c = PeerClient("127.0.0.1", port, paired.fingerprint).connect("phone-1", paired.computerId, paired.key)
        c.sync(com.hubidubitask.core.peer.SyncRequest("phone-1", 0, emptyList()))
        first.stop()
        // A restart forgets every session; the phone's next request gets 410 and opens a new one.
        serveAt(port)
        store.put(EntityRecord("t9", "next_action", "2026-09-27T10:00:00Z", mapOf("text" to "after restart")))
        val resp = c.sync(com.hubidubitask.core.peer.SyncRequest("phone-1", 0, emptyList()))
        assertTrue(resp.changes.any { it.record.id == "t9" })
    }

    private fun serveAt(port: Int) =
        PeerServer(store, "Test Mac", keys, listenPort = port, announce = false) {}.also { it.start(); servers += it }

    @Test
    fun `a paired phone can only upload what the computer asked for`() {
        val paired = pair()
        val opener = PeerSession.Opener(paired.key, "phone-1", server.deviceId)
        val answer = raw.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/peer/v2/hello")
            .header("X-Peer", "phone-1").post(opener.hello.toRequestBody()).build()).execute().use { it.body!!.bytes() }
        val session = assertNotNull(opener.open(answer))
        val (seq, body) = session.seal(JSONObject().put("op", "blob_put").put("sha", "ab".repeat(32))
            .put("offset", 0).put("last", true), ByteArray(10))
        val reply = raw.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/peer/v2/call")
            .header("X-Peer", "phone-1").header("X-Session", session.sidHex)
            .post(body.toRequestBody()).build()).execute().use { it.body!!.bytes() }
        assertEquals(409, assertNotNull(session.openAnswer(seq, reply)).meta.getInt("status"))
        assertTrue(store.blobs.listFiles()!!.none { it.name.startsWith(".part-") })
    }

    @Test
    fun `the old endpoints are gone`() {
        assertEquals(404, post("/peer/pair", "{}".toByteArray()))
        assertEquals(404, post("/peer/sync", "{}".toByteArray()))
        assertEquals(404, post("/peer/blob/" + "a".repeat(64), ByteArray(1)))
    }

    @Test
    fun `addresses off the home network are turned away`() {
        val closed = serve(Store(Files.createTempDirectory("mac3").toFile()), MemoryPeerKeys(), admit = { false })
        closed.desk.open("ABCD-EFGH")
        assertEquals(403, post("/peer/v2/pair/start", "{}".toByteArray(), s = closed))
        assertNull(PeerClient("127.0.0.1", closed.port, null).pairingOpen()?.takeIf { it })
    }

    @Test
    fun `phones paired the old way are dropped, their sync progress kept`() {
        val d = Files.createTempDirectory("mac4").toFile()
        Store(d).setCursor("phone-old", Cursor(theirs = 7, mine = 9))
        DriverManager.getConnection("jdbc:sqlite:" + File(d, "hubidubios.db").path).use { db ->
            db.createStatement().use {
                it.executeUpdate("INSERT INTO peers (id, name, token, paired_at) VALUES ('phone-old', 'Old', '${"ab".repeat(32)}', '2026-10-01T00:00:00Z')")
            }
        }
        val reopened = Store(d)
        assertTrue(reopened.peers().isEmpty())
        assertEquals(Cursor(7, 9), reopened.cursor("phone-old"))
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(d.toPath())))
    }
}
