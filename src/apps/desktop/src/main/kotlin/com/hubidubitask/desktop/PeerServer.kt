package com.hubidubitask.desktop

import com.hubidubitask.core.nowUtc
import com.hubidubitask.core.peer.PairDesk
import com.hubidubitask.core.peer.PairFinish
import com.hubidubitask.core.peer.PairStart
import com.hubidubitask.core.peer.PeerClient
import com.hubidubitask.core.peer.PeerJson
import com.hubidubitask.core.peer.PeerNet
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.core.peer.PeerResponder
import com.hubidubitask.core.peer.PeerSession
import com.hubidubitask.core.peer.PeerSessions
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The computer listens on the LAN; the phone comes to it every minute.
 *
 *   GET  /peer/v2/pair/open     whether a pairing code is open (nothing secret)
 *   POST /peer/v2/pair/start    pairing by typed code, step 1 (see core PairV2)
 *   POST /peer/v2/pair/finish   step 2
 *   POST /peer/v2/hello         opens a sealed session (X-Peer: the phone's id)
 *   POST /peer/v2/call          one sealed request in it (X-Peer, X-Session):
 *                               a sync round, or a chunk of a photo, memo or file
 *
 * Under it all, TLS with a self-signed certificate made on first run; the
 * phone pins it at pairing. Found on the network by Bonjour as
 * `_hubidubios-peer._tcp`. Only home-network addresses are answered, an
 * unknown phone is turned away before its request is read, and every body is
 * read up to a limit.
 */
class PeerServer(
    private val store: Store,
    private val deviceName: String,
    private val keys: PeerKeys = SecretPeerKeys(),
    /** The port to listen on; 0 picks a free one (tests). */
    private val listenPort: Int = PORT,
    /** Who may connect; tests can widen it. */
    private val admit: (InetAddress) -> Boolean = PeerNet::isLocal,
    /** Announce on the LAN by Bonjour (tests don't: it takes seconds to withdraw). */
    private val announce: Boolean = true,
    private val onSynced: () -> Unit,
) {
    val desk = PairDesk()
    private val sessions = PeerSessions()
    private val locks = ConcurrentHashMap<String, Any>()
    /** The blobs each phone was asked to upload (sync's "want"): only those are taken. */
    private val wanted = ConcurrentHashMap<String, MutableSet<String>>()
    val deviceId: String = store.kv("device_id") ?: ("mac-" + PeerPairing.nonce().take(12)).also {
        store.setKv("device_id", it)
    }
    private val ks = loadOrCreateKeystore(File(store.dir, "tls.p12"))
    val fingerprint: String = PeerPairing.sha256Hex((ks.getCertificate("mac") as X509Certificate).encoded)
    private var server: HttpsServer? = null
    /** The Bonjour announcement on each interface and address it is made on ("en0/192.168.1.62"). */
    private val announced = ConcurrentHashMap<String, JmDNS>()
    @Volatile private var announcer: Thread? = null
    /** Set once at stop: JmDNS swallows interrupts, so the announcer checks this too. */
    @Volatile private var stopped = false
    private val announcing = Any()
    val port: Int get() = server?.address?.port ?: listenPort

    fun start() {
        val ssl = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance("PKIX").apply { init(ks, PASS) }.keyManagers, null, null)
        }
        val s = HttpsServer.create(InetSocketAddress(listenPort), 0)
        s.httpsConfigurator = HttpsConfigurator(ssl)
        // Room for slow clients: each holds a thread for at most maxReqTime.
        s.executor = Executors.newFixedThreadPool(16)
        s.createContext("/") { ex -> guard(ex) { send(ex, 404, ERR) } }
        s.createContext("/peer/v2/pair/open") { ex -> guard(ex) { send(ex, if (desk.isOpen()) 200 else 409, "{}") } }
        s.createContext("/peer/v2/pair/start") { ex -> guard(ex) { pairStart(ex) } }
        s.createContext("/peer/v2/pair/finish") { ex -> guard(ex) { pairFinish(ex) } }
        s.createContext("/peer/v2/hello") { ex -> guard(ex) { hello(ex) } }
        s.createContext("/peer/v2/call") { ex -> guard(ex) { call(ex) } }
        // Uploads cut off by a quit or a crash are not resumed across starts.
        store.blobs.listFiles { f -> f.name.startsWith(".part-") }?.forEach { it.delete() }
        s.start()
        server = s
        if (announce) Thread({ advertise() }, "mdns").apply { isDaemon = true }.also { announcer = it }.start()
    }

    fun stop() {
        stopped = true
        announcer?.interrupt(); announcer = null
        // Not waiting for the announcer: a registration under way sees [stopped] and lets go of its own.
        withdraw(announced.keys.toList())
        server?.stop(0)
    }

    /** Unpairs a phone: its key, its open sessions and its record go. */
    fun forget(id: String) {
        keys.remove(id); sessions.drop(id); desk.forget(id); wanted.remove(id); store.removePeer(id)
    }

    /** Addresses the phone could type if it doesn't find this computer, the likeliest first. */
    fun addresses(): List<String> = Lan.forTyping(Lan.nics()).map { "${it.address}:$port" }

    /**
     * Announces this computer by Bonjour on every home-network interface, and
     * keeps doing so as the network changes: Wi-Fi to Ethernet, a new address,
     * a VPN switched on or off. Never on a tunnel ([Lan.announceOn]).
     */
    private fun advertise() {
        while (!stopped && !Thread.currentThread().isInterrupted) {
            runCatching { announce(Lan.announceOn(Lan.nics()).associateBy { "${it.name}/${it.address}" }) }
            try { Thread.sleep(ANNOUNCE_CHECK_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun announce(want: Map<String, Lan.Nic>) = synchronized(announcing) {
        withdraw(announced.keys - want.keys)
        for ((key, nic) in want) {
            if (announced.containsKey(key)) continue
            if (stopped) return@synchronized
            val j = runCatching { JmDNS.create(InetAddress.getByName(nic.address), deviceName) }.getOrNull() ?: continue
            val info = ServiceInfo.create(SERVICE, deviceName, port, 0, 0, mapOf("fp" to fingerprint, "id" to deviceId))
            // A stop that came while this was being made: let it go rather than keep it.
            if (!stopped && runCatching { j.registerService(info) }.isSuccess) {
                announced[key] = j
                if (stopped) withdraw(listOf(key))
            } else runCatching { j.unregisterAllServices(); j.close() }
        }
    }

    private fun withdraw(addresses: Collection<String>) = addresses.forEach { a ->
        announced.remove(a)?.let { runCatching { it.unregisterAllServices(); it.close() } }
    }

    // ---- pairing

    private fun pairStart(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, ERR)
        // Closed: refused before anything is read or parsed.
        if (!desk.isOpen()) return send(ex, 409, ERR)
        val body = body(ex, PAIR_LIMIT) ?: return send(ex, 413, ERR)
        val req = runCatching { PairStart.of(body.decodeToString()) }.getOrNull() ?: return send(ex, 400, ERR)
        when (val out = desk.start(req, fingerprint, deviceId, deviceName, ex.remoteAddress.address.hostAddress)) {
            is PairDesk.Outcome.Started -> send(ex, 200, out.answer.json())
            PairDesk.Outcome.Busy -> send(ex, 429, ERR)
            PairDesk.Outcome.Rejected -> send(ex, 403, ERR)
            else -> send(ex, 409, ERR)
        }
    }

    private fun pairFinish(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, ERR)
        val body = body(ex, PAIR_LIMIT) ?: return send(ex, 413, ERR)
        val req = runCatching { PairFinish.of(body.decodeToString()) }.getOrNull() ?: return send(ex, 400, ERR)
        when (val out = desk.finish(req)) {
            is PairDesk.Outcome.Paired -> {
                // Same answer for a repeated finish; storing again changes nothing. The key
                // is stored before the confirmation goes out: if storing fails, the phone is
                // told nothing (500) rather than believing it is paired.
                keys.put(out.phoneId, out.key)
                sessions.drop(out.phoneId)
                val since = store.peer(out.phoneId)?.pairedAt ?: nowUtc()
                store.addPeer(Store.Peer(out.phoneId, out.name.ifBlank { "Phone" }, since, null))
                onSynced()
                send(ex, 200, out.answer.json())
            }
            PairDesk.Outcome.Rejected -> send(ex, 403, ERR)
            else -> send(ex, 409, ERR)
        }
    }

    // ---- sealed sessions

    /** The paired phone named in X-Peer and its key, or null: then nothing of the body is read. */
    private fun peerOf(ex: HttpExchange): Pair<String, ByteArray>? {
        val id = ex.requestHeaders.getFirst("X-Peer")?.takeIf(PeerPairing::isDeviceId) ?: return null
        if (store.peer(id) == null) return null
        return id to (keys.get(id) ?: return null)
    }

    private fun hello(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, ERR)
        val (peer, key) = peerOf(ex) ?: return send(ex, 401, ERR)
        val body = body(ex, HELLO_LIMIT) ?: return send(ex, 413, ERR)
        val (answer, session) = PeerSession.accept(key, peer, deviceId, body, System.currentTimeMillis())
            ?: return send(ex, 401, ERR)
        sessions.add(peer, session)
        sendBytes(ex, 200, answer)
    }

    private fun call(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, ERR)
        val (peer, _) = peerOf(ex) ?: return send(ex, 401, ERR)
        // 410: the session ended (idle, or this computer restarted); the phone opens a new one.
        val session = ex.requestHeaders.getFirst("X-Session")?.let { sessions.find(peer, it) }
            ?: return send(ex, 410, ERR)
        val body = body(ex, CALL_LIMIT) ?: return send(ex, 413, ERR)
        val (seq, frame) = session.openRequest(body) ?: return send(ex, 400, ERR)
        val (meta, payload) = try {
            // One round per phone at a time: the cursors are read, then moved.
            synchronized(locks.computeIfAbsent(peer) { Any() }) { op(peer, frame) }
        } catch (e: Exception) { JSONObject().put("status", 500) to ByteArray(0) }
        sendBytes(ex, 200, session.sealAnswer(seq, meta, payload))
    }

    private fun op(peer: String, f: PeerSession.Frame): Pair<JSONObject, ByteArray> {
        val m = f.meta
        fun status(code: Int) = JSONObject().put("status", code) to ByteArray(0)
        return when (m.optString("op")) {
            "sync" -> {
                val req = PeerJson.request(f.payload.toString(Charsets.UTF_8))
                val resp = PeerResponder(store) { sha -> store.blob(sha)?.exists() == true }.handle(peer, req)
                if (resp.want.isNotEmpty()) wanted.computeIfAbsent(peer) { ConcurrentHashMap.newKeySet() } += resp.want
                store.touchPeer(peer, nowUtc())
                onSynced()
                JSONObject().put("status", 200) to PeerJson.response(resp).toByteArray(Charsets.UTF_8)
            }
            "blob_have" -> {
                val sha = m.optString("sha")
                val file = store.blob(sha) ?: return status(400)
                if (sha !in wanted[peer].orEmpty()) return status(409)
                val part = File(file.parentFile, ".part-$sha-$peer")
                JSONObject().put("status", 200).put("part", if (part.exists()) part.length() else 0L) to ByteArray(0)
            }
            "blob_put" -> {
                val sha = m.optString("sha")
                val file = store.blob(sha) ?: return status(400)
                // Only a blob this computer asked for, and one at a time per phone.
                if (sha !in wanted[peer].orEmpty()) return status(409)
                val part = File(file.parentFile, ".part-$sha-$peer")
                val offset = m.optLong("offset", -1)
                if (offset == 0L) store.blobs.listFiles { f -> f.name.startsWith(".part-") && f.name.endsWith("-$peer") }
                    ?.forEach { it.delete() }
                if (offset < 0 || offset != (if (part.exists()) part.length() else 0L)) return status(409)
                if (offset + f.payload.size > PeerClient.MAX_BLOB) { part.delete(); return status(413) }
                RandomAccessFile(part, "rw").use { it.seek(offset); it.write(f.payload) }
                if (m.optBoolean("last")) {
                    // Named by its hash, so it must be its hash.
                    if (PeerClient.sha256Hex(part) != sha) { part.delete(); return status(400) }
                    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE)
                    wanted[peer]?.remove(sha)
                    onSynced()
                }
                status(200)
            }
            "blob_get" -> {
                val file = store.blob(m.optString("sha"))?.takeIf { it.exists() } ?: return status(404)
                val offset = m.optLong("offset", -1)
                val size = file.length()
                if (offset < 0 || offset > size) return status(400)
                val n = minOf(PeerClient.CHUNK.toLong(), size - offset).toInt()
                val chunk = ByteArray(n)
                RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(chunk) }
                JSONObject().put("status", 200).put("size", size).put("last", offset + n == size) to chunk
            }
            else -> status(400)
        }
    }

    // ---- plumbing

    /** The request body, or null when it is over [limit] (chunked bodies included). */
    private fun body(ex: HttpExchange, limit: Int): ByteArray? =
        ex.requestBody.readNBytes(limit + 1).takeIf { it.size <= limit }

    private fun guard(ex: HttpExchange, body: () -> Unit) {
        try {
            // Answered only on the home network, whatever networks this computer is on.
            if (!admit(ex.remoteAddress.address)) send(ex, 403, ERR) else body()
        } catch (e: Exception) {
            runCatching { send(ex, 500, ERR) }
        } finally { ex.close() }
    }

    private fun send(ex: HttpExchange, code: Int, json: String) {
        ex.responseHeaders.add("Content-Type", "application/json")
        val b = json.toByteArray()
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun sendBytes(ex: HttpExchange, code: Int, b: ByteArray) {
        ex.responseHeaders.add("Content-Type", "application/octet-stream")
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    companion object {
        const val PORT = PeerPairing.PORT
        /** The Bonjour service phones look for; its name is from the HubidubiOS days, kept so they still find it. */
        private const val SERVICE = "_hubidubios-peer._tcp.local."
        /** How often the network is checked for a change to announce on. */
        private const val ANNOUNCE_CHECK_MS = 10_000L
        private const val ERR = """{"error":"refused"}"""
        private const val PAIR_LIMIT = 64 * 1024
        private const val HELLO_LIMIT = 8 * 1024
        /** A sync batch or one blob chunk, sealed. */
        private const val CALL_LIMIT = 32 shl 20

        init {
            // A request (and an answer) must finish in this many seconds, so a
            // client that sends a byte a minute can't hold a thread. Read once,
            // when the JDK's server first loads; the app's launcher sets them too.
            System.setProperty("sun.net.httpserver.maxReqTime",
                System.getProperty("sun.net.httpserver.maxReqTime") ?: "30")
            System.setProperty("sun.net.httpserver.maxRspTime",
                System.getProperty("sun.net.httpserver.maxRspTime") ?: "120")
        }

        // The file is the secret, not this. It keeps the HubidubiOS-era password:
        // with another one the existing tls.p12 would not open, a new certificate
        // would be made, and every phone that pinned the old one would stop syncing.
        private val PASS = "hubidubios".toCharArray()

        fun loadOrCreateKeystore(file: File): KeyStore {
            val ks = KeyStore.getInstance("PKCS12")
            if (file.exists()) { file.inputStream().use { ks.load(it, PASS) }; return ks }
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val name = X500Name("CN=HubidubiTask Mac")
            val now = System.currentTimeMillis()
            val cert = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000),
                    Date(now + 20L * 365 * 86_400_000), name, kp.public)
                    .build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)))
            ks.load(null, null)
            ks.setKeyEntry("mac", kp.private, PASS, arrayOf(cert))
            file.outputStream().use { ks.store(it, PASS) }
            file.setReadable(false, false); file.setReadable(true, true)
            return ks
        }
    }
}
