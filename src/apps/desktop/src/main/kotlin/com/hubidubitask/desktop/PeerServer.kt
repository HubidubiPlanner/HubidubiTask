package com.hubidubitask.desktop

import com.hubidubitask.core.nowUtc
import com.hubidubitask.core.peer.PairRequest
import com.hubidubitask.core.peer.PairingDesk
import com.hubidubitask.core.peer.PeerJson
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.core.peer.PeerResponder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The Mac listens on the LAN; the phone comes to it every minute.
 *
 *   POST /peer/pair         pairing by typed code (see core PairingDesk)
 *   POST /peer/sync         one sync round, Bearer token
 *   PUT  /peer/blob/<sha>   a photo or voice memo the phone holds
 *   GET  /peer/blob/<sha>   and back
 *
 * TLS with a self-signed certificate made on first run; the phone pins its
 * fingerprint at pairing, so the certificate is the Mac's identity. Found on
 * the network by Bonjour as `_hubidubios-peer._tcp`.
 */
class PeerServer(
    private val store: Store,
    private val deviceName: String,
    private val onSynced: () -> Unit,
) {
    val desk = PairingDesk()
    val port = 5244
    val deviceId: String = store.kv("device_id") ?: ("mac-" + PeerPairing.nonce().take(12)).also {
        store.setKv("device_id", it)
    }
    private val ks = loadOrCreateKeystore(File(store.dir, "tls.p12"))
    val fingerprint: String = PeerPairing.sha256Hex((ks.getCertificate("mac") as X509Certificate).encoded)
    private var server: HttpsServer? = null
    private var mdns: JmDNS? = null

    fun start() {
        val ssl = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance("PKIX").apply { init(ks, PASS) }.keyManagers, null, null)
        }
        val s = HttpsServer.create(InetSocketAddress(port), 0)
        s.httpsConfigurator = HttpsConfigurator(ssl)
        s.executor = Executors.newFixedThreadPool(4)
        s.createContext("/peer/pair") { ex -> guard(ex) { pair(ex) } }
        s.createContext("/peer/sync") { ex -> guard(ex) { sync(ex) } }
        s.createContext("/peer/blob/") { ex -> guard(ex) { blob(ex) } }
        s.start()
        server = s
        Thread({ advertise() }, "mdns").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { mdns?.unregisterAllServices(); mdns?.close() }
        server?.stop(0)
    }

    /** Addresses the phone could type if Bonjour is blocked on the network. */
    fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
        .map { "${it.hostAddress}:$port" }

    private fun advertise() = runCatching {
        val addr = addresses().firstOrNull()?.substringBefore(':')?.let(InetAddress::getByName)
            ?: InetAddress.getLocalHost()
        mdns = JmDNS.create(addr, deviceName).also {
            it.registerService(ServiceInfo.create("_hubidubios-peer._tcp.local.", deviceName, port,
                0, 0, mapOf("fp" to fingerprint, "id" to deviceId)))
        }
    }

    // ---- handlers

    private fun pair(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, "{}")
        val req = PairRequest.of(ex.requestBody.readBytes().decodeToString())
        when (val out = desk.answer(req, fingerprint, deviceId, deviceName)) {
            is PairingDesk.Outcome.Paired -> {
                store.addPeer(Store.Peer(req.deviceId, req.name.ifBlank { "Phone" },
                    out.response.token, nowUtc(), null))
                onSynced()
                send(ex, 200, out.response.json())
            }
            PairingDesk.Outcome.NotOpen -> send(ex, 409, """{"error":"not pairing"}""")
            PairingDesk.Outcome.Rejected -> send(ex, 403, """{"error":"wrong code"}""")
        }
    }

    private fun peerOf(ex: HttpExchange): Store.Peer? =
        ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.let(store::peerByToken)

    private fun sync(ex: HttpExchange) {
        val peer = peerOf(ex) ?: return send(ex, 401, """{"error":"not paired"}""")
        val req = PeerJson.request(ex.requestBody.readBytes().decodeToString())
        val resp = PeerResponder(store) { sha -> store.blob(sha)?.exists() == true }.handle(peer.id, req)
        store.touchPeer(peer.id, nowUtc())
        send(ex, 200, PeerJson.response(resp))
        onSynced()
    }

    private fun blob(ex: HttpExchange) {
        peerOf(ex) ?: return send(ex, 401, "{}")
        val sha = ex.requestURI.path.substringAfterLast('/')
        val file = store.blob(sha) ?: return send(ex, 400, "{}")
        when (ex.requestMethod) {
            "PUT" -> {
                val bytes = ex.requestBody.readBytes()
                // Named by its hash, so it must be its hash.
                if (PeerPairing.sha256Hex(bytes) != sha) return send(ex, 400, """{"error":"hash"}""")
                file.writeBytes(bytes); send(ex, 200, "{}"); onSynced()
            }
            "GET" -> if (file.exists()) {
                ex.sendResponseHeaders(200, file.length()); ex.responseBody.use { it.write(file.readBytes()) }
            } else send(ex, 404, "{}")
            else -> send(ex, 405, "{}")
        }
    }

    private fun guard(ex: HttpExchange, body: () -> Unit) {
        try { body() } catch (e: Exception) {
            runCatching { send(ex, 500, """{"error":"${e.javaClass.simpleName}"}""") }
        } finally { ex.close() }
    }

    private fun send(ex: HttpExchange, code: Int, json: String) {
        val b = json.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    companion object {
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
