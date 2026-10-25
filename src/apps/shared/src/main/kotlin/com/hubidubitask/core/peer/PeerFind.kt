package com.hubidubitask.core.peer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/**
 * Finding a computer on the home network without Bonjour.
 *
 * Bonjour is multicast, and multicast gets lost: guest and mesh Wi-Fi drop
 * it, some tablets never deliver it, and a computer with a VPN may announce
 * itself on the tunnel. Every computer listens on [PeerPairing.PORT], so the
 * phone can also knock on that port at each address of its own Wi-Fi
 * network. A knock is a TCP connect and nothing more; pairing and sync then
 * prove who answered, as they do for a computer Bonjour found.
 */
object PeerSweep {
    /** Most addresses one sweep covers: a /22. A larger network is swept around the phone's own /24. */
    private const val MAX_PREFIX_BITS = 10

    /**
     * The other addresses of [self]'s network ([prefix] bits): its /24 when
     * the network is larger than a /22, never the network's own address, its
     * broadcast address or [self]. Nothing for a /31 or /32: a link with no
     * one else on it, as VPNs give.
     */
    fun hosts(self: Inet4Address, prefix: Int): List<String> {
        if (prefix > 30) return emptyList()
        val bits = if (prefix >= 32 - MAX_PREFIX_BITS) prefix else 24
        val me = self.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val size = 1L shl (32 - bits)
        val net = me and (size - 1).inv() and 0xffffffffL
        return (1 until size - 1).map { net + it }.filter { it != me }.map { a ->
            "${a shr 24 and 0xff}.${a shr 16 and 0xff}.${a shr 8 and 0xff}.${a and 0xff}"
        }
    }

    /**
     * Those of [hosts] that take a connection on [port] within [timeoutMs].
     * All are tried at once ([batch] at a time), so a sweep of a /24 takes
     * about [timeoutMs] whatever is there. [bind] puts each socket on a
     * network before it connects (the phone's Wi-Fi).
     */
    fun open(hosts: List<String>, port: Int = PeerPairing.PORT, timeoutMs: Long = 2000,
             batch: Int = 256, bind: (SocketChannel) -> Unit = {}): List<String> {
        val found = ArrayList<String>()
        for (chunk in hosts.chunked(batch)) Selector.open().use { sel ->
            val channels = ArrayList<SocketChannel>()
            try {
                var waiting = 0
                for (h in chunk) runCatching {
                    val ch = SocketChannel.open().also { channels += it }
                    bind(ch)
                    ch.configureBlocking(false)
                    if (ch.connect(InetSocketAddress(InetAddress.getByName(h), port))) found += h
                    else { ch.register(sel, SelectionKey.OP_CONNECT, h); waiting++ }
                }
                val until = System.nanoTime() + timeoutMs * 1_000_000
                while (waiting > 0) {
                    val left = (until - System.nanoTime()) / 1_000_000
                    if (left <= 0 || sel.select(left) < 0) break
                    val keys = sel.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val k = keys.next(); keys.remove()
                        if (runCatching { (k.channel() as SocketChannel).finishConnect() }.getOrDefault(false))
                            found += k.attachment() as String
                        k.cancel(); waiting--
                    }
                }
            } finally {
                channels.forEach { runCatching { it.close() } }
            }
        }
        return found
    }
}

/**
 * Where the phone looks for computers, in order: the address typed by hand,
 * what Bonjour found, and what a sweep of the Wi-Fi found. Every source is
 * asked again on each call, so an address typed while the search runs is
 * tried on the next pass; the sweep, which takes a couple of seconds, runs at
 * most every [sweepEveryMs].
 */
class PeerCandidates(
    private val typed: () -> PeerAddress? = { null },
    private val bonjour: () -> List<PeerAddress> = { emptyList() },
    private val sweep: () -> List<PeerAddress> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sweepEveryMs: Long = 30_000,
) : () -> List<PeerAddress> {
    private var swept: List<PeerAddress> = emptyList()
    private var sweptAt: Long? = null

    @Synchronized
    override fun invoke(): List<PeerAddress> {
        val first = listOfNotNull(runCatching(typed).getOrNull()) + runCatching(bonjour).getOrDefault(emptyList())
        val last = sweptAt
        if (last == null || clock() - last >= sweepEveryMs) {
            swept = runCatching(sweep).getOrDefault(emptyList())
            sweptAt = clock()
        }
        return (first + swept).distinctBy { it.host to it.port }
    }
}
