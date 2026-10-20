package com.hubidubitask.core.peer

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Which addresses count as the home network for the computer's sync server. */
object PeerNet {
    /**
     * Loopback, link-local, the private IPv4 ranges (10/8, 172.16/12,
     * 192.168/16), CGNAT 100.64/10 (Tailscale), and IPv6 unique-local
     * fc00::/7; an IPv4-mapped IPv6 address is judged by its IPv4 part.
     * Checked by hand: Java's isSiteLocalAddress misses ULA and CGNAT.
     */
    fun isLocal(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress) return true
        val b = a.address
        if (a is Inet6Address) {
            val mapped = b.size == 16 && (0 until 10).all { b[it] == 0.toByte() } &&
                b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
            if (mapped) return isLocal(InetAddress.getByAddress(b.copyOfRange(12, 16)))
            return (b[0].toInt() and 0xfe) == 0xfc
        }
        if (a !is Inet4Address) return false
        val x = b[0].toInt() and 0xff
        val y = b[1].toInt() and 0xff
        return x == 10 || (x == 172 && y in 16..31) || (x == 192 && y == 168) || (x == 100 && y in 64..127)
    }
}
