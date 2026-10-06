package com.hubidubios.mobile.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.hubidubios.core.peer.PairRequest
import com.hubidubios.core.peer.PairResponse
import com.hubidubios.core.peer.PairingClient
import com.hubidubios.core.peer.PeerJson
import com.hubidubios.core.peer.PeerPairing
import com.hubidubios.core.peer.PeerStore
import com.hubidubios.core.peer.PeerSyncer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The phone's link to a paired Mac: finding it on the LAN, pairing by typed
 * code, and one sync round a minute. The phone always calls the Mac; the Mac
 * only listens.
 */
object MacLink {

    data class Mac(val id: String, val name: String, val host: String, val port: Int,
                   val fingerprint: String, val token: String, val lastSync: String? = null)

    private const val PREFS = "mac_peer"
    private const val SERVICE = "_hubidubios-peer._tcp"

    // ---- this phone, and the Mac it is paired with

    fun deviceId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("self_id", null) ?: ("phone-" + PeerPairing.nonce().take(12)).also {
            p.edit().putString("self_id", it).apply()
        }
    }

    fun load(context: Context): Mac? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Mac(p.getString("id", null) ?: return null, p.getString("name", "Computer")!!,
            p.getString("host", null) ?: return null, p.getInt("port", 5244),
            p.getString("fp", null) ?: return null, p.getString("token", null) ?: return null,
            p.getString("last_sync", null))
    }

    fun save(context: Context, m: Mac) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("id", m.id).putString("name", m.name).putString("host", m.host)
            .putInt("port", m.port).putString("fp", m.fingerprint).putString("token", m.token)
            .putString("last_sync", m.lastSync).apply()
    }

    fun forget(context: Context) {
        val self = deviceId(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("self_id", self).apply()
    }

    // ---- finding Macs

    data class Found(val name: String, val host: String, val port: Int)

    /** Macs announcing themselves on the LAN, gathered for [millis]. */
    fun discover(context: Context, millis: Long = 2500): List<Found> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val out = java.util.Collections.synchronizedList(ArrayList<Found>())
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(si: NsdServiceInfo) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(si, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            s.host?.hostAddress?.let { out += Found(s.serviceName, it, s.port) }
                        }
                    })
                }
            }
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
        CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(l) }
        return out.distinctBy { it.host to it.port }
    }

    /** "192.168.1.62:5244" or "192.168.1.62" typed by hand, when Bonjour is blocked. */
    fun parseAddress(s: String): Found? {
        val t = s.trim().removePrefix("https://").trimEnd('/')
        if (t.isBlank()) return null
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "5244").toIntOrNull() ?: return null
        return Found(host, host, port)
    }

    // ---- TLS: learn the Mac's certificate once, then pin it

    private class Capture(private val pin: String?) : X509TrustManager {
        @Volatile var seen: String? = null
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>, a: String?) {
            val fp = PeerPairing.sha256Hex(chain[0].encoded)
            seen = fp
            if (pin != null && fp != pin) throw javax.net.ssl.SSLPeerUnverifiedException("not the paired computer")
        }
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    private fun client(tm: Capture, readSeconds: Long = 30): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(readSeconds, TimeUnit.SECONDS).build()
    }

    private val json = "application/json".toMediaType()

    // ---- pairing

    sealed interface PairOutcome {
        data class Paired(val mac: Mac) : PairOutcome
        /** Reached, but it isn't waiting for a code (yet). */
        data object NotReady : PairOutcome
        data object WrongCode : PairOutcome
        data object Unreachable : PairOutcome
    }

    /** One attempt against one Mac. Network; off the main thread. */
    fun pair(context: Context, code: String, at: Found, phoneName: String): PairOutcome {
        val base = "https://${at.host}:${at.port}"
        // First contact only to see the certificate; then the same one is pinned
        // for the pairing request, so it can't be swapped between the two.
        val probe = Capture(null)
        runCatching { client(probe).newCall(Request.Builder().url("$base/peer/hello").build()).execute().close() }
        val fp = probe.seen ?: return PairOutcome.Unreachable
        val req = PairingClient.request(code, deviceId(context), phoneName, fp)
        val resp = try {
            client(Capture(fp)).newCall(Request.Builder().url("$base/peer/pair")
                .post(req.json().toRequestBody(json)).build()).execute()
        } catch (e: Exception) { return PairOutcome.Unreachable }
        resp.use { r ->
            return when (r.code) {
                200 -> {
                    val answer = PairResponse.of(r.body!!.string())
                    if (!PairingClient.verify(code, req, answer, fp)) PairOutcome.WrongCode
                    else PairOutcome.Paired(Mac(answer.deviceId, answer.name.ifBlank { at.name },
                        at.host, at.port, fp, answer.token).also { save(context, it) })
                }
                409 -> PairOutcome.NotReady
                403 -> PairOutcome.WrongCode
                else -> PairOutcome.Unreachable
            }
        }
    }

    // ---- sync

    data class Round(val sent: Int, val received: Int)

    /** A photo, recording or file the Mac held and this phone now has too. */
    data class Fetched(val sha: String, val file: File, val mime: String, val noteId: String)

    /**
     * One sync round with the paired Mac. If it isn't where it was, look for it
     * on the LAN by its certificate (its address may have changed) and try again.
     */
    fun sync(context: Context, store: PeerStore, blobsDir: File,
             onFetched: (Fetched) -> Unit = {}): Round? {
        var mac = load(context) ?: return null
        val attempt = { m: Mac -> runCatching { round(context, m, store, blobsDir, onFetched) } }
        var result = attempt(mac)
        if (result.isFailure) {
            val moved = discover(context).firstOrNull { f ->
                val probe = Capture(null)
                runCatching { client(probe).newCall(Request.Builder()
                    .url("https://${f.host}:${f.port}/peer/hello").build()).execute().close() }
                probe.seen == mac.fingerprint
            }
            if (moved != null) {
                mac = mac.copy(host = moved.host, port = moved.port).also { save(context, it) }
                result = attempt(mac)
            }
        }
        return result.getOrNull()?.also {
            save(context, mac.copy(lastSync = com.hubidubios.core.nowUtc()))
        }
    }

    private fun round(context: Context, mac: Mac, store: PeerStore, blobsDir: File,
                      onFetched: (Fetched) -> Unit): Round {
        val http = client(Capture(mac.fingerprint), readSeconds = 60)
        val base = "https://${mac.host}:${mac.port}"
        fun auth(b: Request.Builder) = b.header("Authorization", "Bearer ${mac.token}")
        // What the Mac sends that names bytes this phone lacks: a photo, memo
        // or file captured there. Fetched after the round, so the phone holds
        // what the computer holds.
        val wanted = LinkedHashMap<String, Pair<String, String>>()   // sha -> (mime, record id)
        val watching = object : PeerStore by store {
            override fun applyFromPeer(record: com.hubidubios.core.EntityRecord, peer: String) {
                store.applyFromPeer(record, peer)
                val sha = record.fields["media_sha256"]
                if (!record.deleted && !sha.isNullOrBlank() && !File(blobsDir, sha).exists())
                    wanted[sha] = (record.fields["media_mime"] ?: "application/octet-stream") to record.id
            }
        }
        val report = PeerSyncer(
            watching, peer = mac.id, self = deviceId(context),
            send = { req ->
                http.newCall(auth(Request.Builder().url("$base/peer/sync"))
                    .post(PeerJson.request(req).toRequestBody(json)).build()).execute().use { r ->
                    if (r.code == 401) throw IllegalStateException("the computer no longer knows this phone")
                    if (!r.isSuccessful) throw java.io.IOException("the computer answered ${r.code}")
                    PeerJson.response(r.body!!.string())
                }
            },
            // Offer the photos and voice memos we hold for what we just sent.
            blobsOf = { changes ->
                changes.mapNotNull { it.record.fields["media_sha256"] }
                    .filter { File(blobsDir, it).exists() }.distinct()
            },
            upload = { shas ->
                shas.forEach { sha ->
                    val f = File(blobsDir, sha)
                    if (f.exists()) runCatching {
                        http.newCall(auth(Request.Builder().url("$base/peer/blob/$sha"))
                            .put(f.readBytes().toRequestBody("application/octet-stream".toMediaType()))
                            .build()).execute().close()
                    }
                }
            },
        ).round()
        wanted.forEach { (sha, meta) ->
            runCatching {
                http.newCall(auth(Request.Builder().url("$base/peer/blob/$sha")).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val bytes = r.body!!.bytes()
                    // Named by its hash, so it must be its hash.
                    if (PeerPairing.sha256Hex(bytes) != sha) return@use
                    blobsDir.mkdirs()
                    val f = File(blobsDir, sha)
                    f.writeBytes(bytes)
                    onFetched(Fetched(sha, f, meta.first, meta.second))
                }
            }
        }
        return Round(report.sent, report.received)
    }
}
