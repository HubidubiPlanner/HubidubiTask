package com.hubidubitask.mobile.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.hubidubitask.core.peer.PeerAddress
import com.hubidubitask.core.peer.PeerClient
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.core.peer.PeerStore
import com.hubidubitask.mobile.security.Keystore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's link to its paired computers: finding them on the LAN and
 * keeping what pairing gave. The talking itself (pairing, sealed sync
 * sessions) is [PeerClient] in the shared code. The phone always calls the
 * computer; the computer only listens.
 *
 * Each computer's sync key lives sealed by the Android Keystore; the list in
 * plain preferences holds only names, addresses and certificate pins.
 */
object MacLink {

    data class Mac(val id: String, val name: String, val host: String, val port: Int,
                   val fingerprint: String, val lastSync: String? = null)

    private const val PREFS = "mac_peer"
    private const val LIST = "macs"
    // The Bonjour name from the HubidubiOS days: kept, so the computer is found as before.
    private const val SERVICE = "_hubidubios-peer._tcp"

    private fun keyName(id: String) = "mac_key_$id"

    // ---- this phone, and the computers it is paired with

    fun deviceId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("self_id", null) ?: ("phone-" + PeerPairing.nonce().take(12)).also {
            p.edit().putString("self_id", it).commit()
        }
    }

    /**
     * Every computer this phone is paired with, in the order they were paired.
     * Pairings from before pairing v2 (a bearer token, no sealed key) are void
     * and dropped here, with their token: the computer is paired again. An
     * entry stays as long as its sealed key is there, even if the Keystore
     * fails to open it for a moment.
     */
    @Synchronized
    fun all(context: Context): List<Mac> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = p.getString(LIST, null)?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
        val entries = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
        val macs = entries.mapNotNull { o ->
            macOf(o)?.takeIf { !o.has("token") && Keystore.hasSecret(context, keyName(it.id)) }
        }
        val legacy = listOf("id", "name", "host", "port", "fp", "token", "last_sync").any(p::contains)
        if (legacy || macs.size != entries.size) write(context, macs)
        return macs
    }

    /** The sync key pairing gave for computer [id]. */
    fun key(context: Context, id: String): ByteArray? =
        runCatching { Keystore.getSecret(context, keyName(id)) }.getOrNull()?.let(::unhex)?.takeIf { it.size == 32 }

    /** Keep [m] (a new computer joins the list, one paired again replaces itself) and, when given, its [key]. */
    @Synchronized
    fun save(context: Context, m: Mac, key: ByteArray? = null) {
        // The key first, written through: a list entry never points at a key that isn't there.
        if (key != null) Keystore.putSecret(context, keyName(m.id), hex(key), now = true)
        val list = all(context)
        write(context, if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m)
    }

    /** Let go of one computer and its key; the others stay paired. */
    @Synchronized
    fun forget(context: Context, id: String) {
        write(context, all(context).filter { it.id != id })
        Keystore.removeSecret(context, keyName(id))
    }

    private fun write(context: Context, macs: List<Mac>) {
        val a = org.json.JSONArray()
        macs.forEach { m ->
            a.put(org.json.JSONObject().put("id", m.id).put("name", m.name).put("host", m.host)
                .put("port", m.port).put("fp", m.fingerprint)
                .put("last_sync", m.lastSync ?: org.json.JSONObject.NULL))
        }
        // The single-computer keys of old versions go, and with them any plain token.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("id").remove("name").remove("host").remove("port").remove("fp").remove("token")
            .remove("last_sync").putString(LIST, a.toString()).commit()
    }

    private fun macOf(o: org.json.JSONObject): Mac? {
        fun s(k: String) = o.optString(k).takeIf { it.isNotEmpty() && !o.isNull(k) }
        return Mac(s("id") ?: return null, s("name") ?: "Computer", s("host") ?: return null,
            o.optInt("port", 5244), s("fp") ?: return null, s("last_sync"))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0 || s.any { it !in '0'..'9' && it !in 'a'..'f' }) null
        else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---- finding computers

    /** Computers announcing themselves on the LAN, gathered for [millis]. */
    fun discover(context: Context, millis: Long = 2500): List<PeerAddress> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val out = java.util.Collections.synchronizedList(ArrayList<PeerAddress>())
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
                            s.host?.hostAddress?.let { out += PeerAddress(s.serviceName, it, s.port) }
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
    fun parseAddress(s: String): PeerAddress? {
        val t = s.trim().removePrefix("https://").trimEnd('/')
        if (t.isBlank()) return null
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "5244").toIntOrNull() ?: return null
        return PeerAddress(host, host, port)
    }

    // ---- pairing

    /** Keeps what a finished pairing gave: the computer, where it was, its pin and its key. */
    fun keep(context: Context, at: PeerAddress, paired: PeerClient.PairOutcome.Paired): Mac =
        Mac(paired.computerId, paired.name.ifBlank { at.name }, at.host, at.port, paired.fingerprint)
            .also { save(context, it, paired.key) }

    // ---- sync

    /** One round at a time per computer, whoever asks (the minute timer, Sync now). */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * One sync round with each paired computer, in turn. Null when none is
     * paired or none answered; otherwise what went and came, all told.
     */
    fun sync(context: Context, store: PeerStore, blobsDir: File,
             onFetched: (PeerClient.Fetched) -> Unit = {}): PeerClient.Round? {
        var total: PeerClient.Round? = null
        for (mac in all(context)) {
            val key = key(context, mac.id) ?: continue
            val r = synchronized(locks.computeIfAbsent(mac.id) { Any() }) {
                syncWith(context, mac, key, store, blobsDir, onFetched)
            } ?: continue
            total = PeerClient.Round((total?.sent ?: 0) + r.sent, (total?.received ?: 0) + r.received)
        }
        return total
    }

    /**
     * One round with one computer. If it isn't where it was, look for it on the
     * LAN by its certificate (its address may have changed) and try again.
     */
    private fun syncWith(context: Context, paired: Mac, key: ByteArray, store: PeerStore, blobsDir: File,
                         onFetched: (PeerClient.Fetched) -> Unit): PeerClient.Round? {
        var mac = paired
        val me = deviceId(context)
        val attempt = { m: Mac ->
            runCatching { PeerClient(m.host, m.port, m.fingerprint).round(store, me, m.id, key, blobsDir, onFetched) }
        }
        var result = attempt(mac)
        if (result.exceptionOrNull() is PeerClient.NotPaired) return null
        if (result.isFailure) {
            val moved = discover(context).firstOrNull { f ->
                PeerClient(f.host, f.port, null).let { it.pairingOpen(); it.seen == mac.fingerprint }
            }
            if (moved != null) {
                mac = mac.copy(host = moved.host, port = moved.port).also { save(context, it) }
                result = attempt(mac)
            }
        }
        return result.getOrNull()?.also {
            save(context, mac.copy(lastSync = com.hubidubitask.core.nowUtc()))
        }
    }
}
