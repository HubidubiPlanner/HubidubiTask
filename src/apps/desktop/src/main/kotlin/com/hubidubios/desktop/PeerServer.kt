package com.hubidubios.desktop

import com.hubidubios.core.nowUtc
import com.hubidubios.core.peer.PairRequest
import com.hubidubios.core.peer.PairingDesk
import com.hubidubios.core.peer.PeerJson
import com.hubidubios.core.peer.PeerPairing
import com.hubidubios.core.peer.PeerResponder
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
 *   GET  /peer/v1/models, POST /peer/v1/chat/completions
 *                           the Harness agent (Claude Code here) for the phone,
 *                           in the OpenAI shape; tools only when the request
 *                           sets "hubidubios_tools": true
 *
 * TLS with a self-signed certificate made on first run; the phone pins its
 * fingerprint at pairing, so the certificate is the Mac's identity. Found on
 * the network by Bonjour as `_hubidubios-peer._tcp`.
 */
class PeerServer(
    private val store: Store,
    private val deviceName: String,
    private val harness: com.hubidubios.desktop.harness.Harness? = null,
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
        s.createContext("/peer/v1/") { ex -> guard(ex) { agent(ex) } }
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

    /** The Harness for a paired phone: the same Claude Code run this app uses. */
    private fun agent(ex: HttpExchange) {
        peerOf(ex) ?: return send(ex, 401, """{"error":{"message":"not paired"}}""")
        val h = harness ?: return send(ex, 404, "{}")
        when (ex.requestURI.path.trimEnd('/')) {
            "/peer/v1/models" -> {
                val health = h.health()
                if (!health.connected) return send(ex, 503, org.json.JSONObject()
                    .put("error", org.json.JSONObject().put("message", health.error)).toString())
                send(ex, 200, """{"object":"list","data":[{"id":"${com.hubidubios.desktop.harness.Harness.MODEL}","object":"model"}]}""")
            }
            "/peer/v1/chat/completions" -> {
                if (ex.requestMethod != "POST") return send(ex, 405, "{}")
                val req = org.json.JSONObject(ex.requestBody.readBytes().decodeToString())
                val arr = req.getJSONArray("messages")
                val messages = (0 until arr.length()).map { i -> arr.getJSONObject(i).let {
                    com.hubidubios.core.agent.Message(it.optString("role"), it.optString("content")) } }
                val text = try {
                    h.ask(messages, tools = req.optBoolean("hubidubios_tools", false))
                } catch (e: com.hubidubios.core.agent.AgentError) {
                    return send(ex, 502, org.json.JSONObject().put("error",
                        org.json.JSONObject().put("message", e.message)).toString())
                }
                send(ex, 200, org.json.JSONObject().put("object", "chat.completion")
                    .put("model", com.hubidubios.desktop.harness.Harness.MODEL)
                    .put("choices", org.json.JSONArray().put(org.json.JSONObject().put("index", 0)
                        .put("finish_reason", "stop").put("message", org.json.JSONObject()
                            .put("role", "assistant").put("content", text)))).toString())
            }
            else -> send(ex, 404, "{}")
        }
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
        private val PASS = "hubidubios".toCharArray()   // the file is the secret, not this

        fun loadOrCreateKeystore(file: File): KeyStore {
            val ks = KeyStore.getInstance("PKCS12")
            if (file.exists()) { file.inputStream().use { ks.load(it, PASS) }; return ks }
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val name = X500Name("CN=HubidubiOS Mac")
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
