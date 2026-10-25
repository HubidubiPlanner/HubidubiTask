package com.hubidubitask.mobile.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.hubidubitask.core.peer.PeerAddress
import com.hubidubitask.core.peer.PeerCandidates
import com.hubidubitask.core.peer.PeerClient
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.core.peer.PeerStore
import com.hubidubitask.core.peer.PeerSweep
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
            o.optInt("port", PeerPairing.PORT), s("fp") ?: return null, s("last_sync"))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0 || s.any { it !in '0'..'9' && it !in 'a'..'f' }) null
        else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---- finding computers

    /**
     * Computers announcing themselves on the LAN, gathered for [millis].
     * Each is resolved in turn: up to Android 13 a second resolve while one
     * runs fails, and with two computers (or one announced twice) one would
     * be lost.
     */
    fun discover(context: Context, millis: Long = 2500): List<PeerAddress> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val seen = java.util.Collections.synchronizedList(ArrayList<NsdServiceInfo>())
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(si: NsdServiceInfo) {}
            override fun onServiceFound(si: NsdServiceInfo) { seen += si }
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
        CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(l) }
        val services = synchronized(seen) { seen.distinctBy { it.serviceName } }
        return services.mapNotNull { resolve(nsd, it) }.distinctBy { it.host to it.port }
    }

    /** One service's address, IPv4 when it has one: a scoped IPv6 address can't go in a URL. */
    private fun resolve(nsd: NsdManager, si: NsdServiceInfo): PeerAddress? {
        val done = CountDownLatch(1)
        var out: PeerAddress? = null
        runCatching {
            @Suppress("DEPRECATION")
            nsd.resolveService(si, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) { done.countDown() }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    val all = if (android.os.Build.VERSION.SDK_INT >= 34) s.hostAddresses
                        else listOfNotNull(@Suppress("DEPRECATION") s.host)
                    val host = (all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull())
                        ?.hostAddress?.takeIf { '%' !in it }
                    if (host != null) out = PeerAddress(s.serviceName, host, s.port)
                    done.countDown()
                }
            })
        }.onFailure { return null }
        done.await(3, TimeUnit.SECONDS)
        return out
    }

    /** The phone's Wi-Fi and Ethernet networks (never a VPN on top of them), each with its IPv4 addresses. */
    private fun lans(context: Context): List<Pair<android.net.Network, List<android.net.LinkAddress>>> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lan = (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            if (!lan) return@mapNotNull null
            n to cm.getLinkProperties(n)?.linkAddresses.orEmpty().filter { it.address is java.net.Inet4Address }
        }
    }

    /**
     * Sockets on the Wi-Fi (or Ethernet) network [host] is in, or null to use
     * the default. Without it, a Wi-Fi without internet loses to mobile data
     * as the default network, and the computer is never reached.
     */
    private fun socketsFor(context: Context, host: String): javax.net.SocketFactory? = runCatching {
        val ip = java.net.InetAddress.getByName(host) as? java.net.Inet4Address ?: return null
        lans(context).firstOrNull { (_, addrs) -> addrs.any { sameNet(it, ip) } }?.first?.socketFactory
    }.getOrNull()

    private fun sameNet(la: android.net.LinkAddress, ip: java.net.Inet4Address): Boolean {
        fun v(a: java.net.InetAddress) = a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val mask = if (la.prefixLength == 0) 0L else (0xffffffffL shl (32 - la.prefixLength)) and 0xffffffffL
        return v(la.address) and mask == v(ip) and mask
    }

    /** A client for the computer at [host]:[port], on the network it is in. */
    fun client(context: Context, host: String, port: Int, pin: String?) =
        PeerClient(host, port, pin, socketsFor(context, host))

    /**
     * Computers found by knocking on [PeerPairing.PORT] at every address of
     * the Wi-Fi (or Ethernet) network: for when Bonjour finds nothing. A
     * couple of seconds; nothing on mobile data or through a VPN.
     */
    fun sweep(context: Context): List<PeerAddress> = lans(context).flatMap { (network, addrs) ->
        val hosts = addrs.flatMap { PeerSweep.hosts(it.address as java.net.Inet4Address, it.prefixLength) }.distinct()
        PeerSweep.open(hosts, bind = { ch -> runCatching { network.bindSocket(ch.socket()) } })
    }.distinct().map { PeerAddress(it, it, PeerPairing.PORT) }

    /**
     * Where a pairing search looks: [typed] (read on every pass, so an
     * address typed during the search counts), Bonjour, and the sweep.
     */
    fun candidates(context: Context, typed: () -> String?): () -> List<PeerAddress> = PeerCandidates(
        typed = { typed()?.let(::parseAddress) },
        bonjour = { discover(context) },
        sweep = { sweep(context) },
    )

    /** "192.168.1.62:5244" or "192.168.1.62" typed by hand, when Bonjour is blocked. */
    fun parseAddress(s: String): PeerAddress? = PeerAddress.typed(s)

    // ---- pairing

    /** Keeps what a finished pairing gave: the computer, where it was, its pin and its key. */
    fun keep(context: Context, at: PeerAddress, paired: PeerClient.PairOutcome.Paired): Mac =
        Mac(paired.computerId, paired.name.ifBlank { at.name }, at.host, at.port, paired.fingerprint)
            .also { save(context, it, paired.key) }

    // ---- sync

    /** The last sweep for computers that weren't where they were, kept a few minutes: they may simply be off. */
    @Volatile private var swept: Pair<Long, List<PeerAddress>>? = null
    private const val SWEEP_EVERY_MS = 5 * 60 * 1000L

    private fun sweptRecently(context: Context): List<PeerAddress> {
        val now = System.currentTimeMillis()
        swept?.let { (at, found) -> if (now - at < SWEEP_EVERY_MS) return found }
        return sweep(context).also { swept = now to it }
    }

    /**
     * Where computer [mac] is now: Bonjour first, then the sweep. Only a
     * finished handshake pinned to its certificate counts, so a device that
     * merely shows the same certificate isn't taken for it. Null when it isn't
     * found.
     */
    private fun relocate(context: Context, mac: Mac): PeerAddress? {
        fun its(f: PeerAddress) = client(context, f.host, f.port, mac.fingerprint).pairingOpen() != null
        discover(context).firstOrNull(::its)?.let { return it }
        return sweptRecently(context).firstOrNull(::its)
    }

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
            runCatching { client(context, m.host, m.port, m.fingerprint).round(store, me, m.id, key, blobsDir, onFetched) }
        }
        var result = attempt(mac)
        if (result.exceptionOrNull() is PeerClient.NotPaired) return null
        if (result.isFailure) {
            val moved = runCatching { relocate(context, mac) }.getOrNull()
            if (moved != null) {
                // The new address is kept only once a round there has worked.
                val there = mac.copy(host = moved.host, port = moved.port)
                result = attempt(there)
                if (result.isSuccess) mac = there
            }
        }
        return result.getOrNull()?.also {
            save(context, mac.copy(lastSync = com.hubidubitask.core.nowUtc()))
        }
    }
}
