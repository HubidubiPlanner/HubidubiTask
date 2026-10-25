package com.hubidubitask.core.peer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Finding a computer without Bonjour: the sweep of the Wi-Fi, and where the pairing search looks. */
class PeerFindTest {
    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun `a home network is swept at every other address of its 24`() {
        val hosts = PeerSweep.hosts(v4("192.168.1.23"), 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.23" in hosts)
        assertFalse("192.168.1.0" in hosts || "192.168.1.255" in hosts)
    }

    @Test
    fun `a small network is swept whole, a large one around the phone`() {
        val small = PeerSweep.hosts(v4("10.0.0.70"), 26)
        assertEquals(61, small.size)
        assertEquals("10.0.0.65" to "10.0.0.126", small.first() to small.last())
        val slash22 = PeerSweep.hosts(v4("192.168.4.10"), 22)
        assertEquals(1021, slash22.size)
        assertEquals("192.168.4.1" to "192.168.7.254", slash22.first() to slash22.last())
        for (prefix in listOf(8, 16, 21)) {
            val around = PeerSweep.hosts(v4("172.16.5.9"), prefix)
            assertEquals(253, around.size, "/$prefix")
            assertTrue(around.all { it.startsWith("172.16.5.") }, "/$prefix")
        }
    }

    @Test
    fun `a link with no one else on it, as a VPN gives, isn't swept`() {
        assertEquals(emptyList(), PeerSweep.hosts(v4("10.64.0.2"), 32))
        assertEquals(emptyList(), PeerSweep.hosts(v4("10.64.0.2"), 31))
        assertEquals(listOf("10.0.0.6"), PeerSweep.hosts(v4("10.0.0.5"), 30))
    }

    @Test
    fun `a sweep finds what listens on the port, and only that`() {
        val closed = ServerSocket(0).use { it.localPort }
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { listening ->
            assertEquals(listOf("127.0.0.1"), PeerSweep.open(listOf("127.0.0.1"), listening.localPort))
            assertEquals(emptyList(), PeerSweep.open(listOf("127.0.0.1"), closed))
        }
    }

    @Test
    fun `a sweep takes about its timeout, however many addresses stay silent`() {
        // TEST-NET-1 and -2 (RFC 5737) are never routed: nothing answers there.
        val silent = (1..254).map { "192.0.2.$it" } + (1..100).map { "198.51.100.$it" }
        val t0 = System.nanoTime()
        assertEquals(emptyList(), PeerSweep.open(silent, PeerPairing.PORT, timeoutMs = 500))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 3000, "took $ms ms")
    }

    @Test
    fun `an address typed during the search counts on the next pass, and the sweep runs every 30 seconds`() {
        var now = 0L
        var typed: String? = null
        var sweeps = 0
        val look = PeerCandidates(
            typed = { typed?.let { PeerAddress(it, it, PeerPairing.PORT) } },
            bonjour = { listOf(PeerAddress("Mac", "192.168.1.62", PeerPairing.PORT)) },
            sweep = {
                sweeps++
                listOf("192.168.1.62", "192.168.1.7").map { PeerAddress(it, it, PeerPairing.PORT) }
            },
            clock = { now },
        )
        assertEquals(listOf("192.168.1.62", "192.168.1.7"), look().map { it.host })
        // The same computer twice is tried once, under Bonjour's name.
        assertEquals("Mac", look().first().name)
        typed = "10.0.0.5"; now = 29_999
        assertEquals(listOf("10.0.0.5", "192.168.1.62", "192.168.1.7"), look().map { it.host })
        assertEquals(1, sweeps)
        now = 30_000
        look()
        assertEquals(2, sweeps)
    }

    @Test
    fun `an address is understood however it is typed or pasted`() {
        fun at(s: String) = PeerAddress.typed(s)?.let { it.host to it.port }
        val mac = "192.168.1.62" to PeerPairing.PORT
        for (s in listOf("192.168.1.62", "192.168.1.62:5244", " 192.168.1.62 : 5244 ", "https://192.168.1.62:5244/",
                         "http://192.168.1.62", "HTTPS://192.168.1.62:5244/peer", "192.168.1.62:5244, 10.0.0.5:5244",
                         "192.168.1.62:"))
            assertEquals(mac, at(s), s)
        assertEquals("192.168.1.62" to 6000, at("192.168.1.62:6000"))
        assertEquals("fd00::1" to PeerPairing.PORT, at("fd00::1"))
        assertEquals("fd00::1" to 6000, at("[fd00::1]:6000"))
        assertEquals("mac.local" to PeerPairing.PORT, at("mac.local"))
        for (s in listOf("", "  ", ":5244", "192.168.1.62:port", "192.168.1.62:70000", "a b?c", "[]:5244"))
            assertEquals(null, at(s), "'$s'")
    }

    @Test
    fun `a source that fails doesn't stop the others`() {
        val look = PeerCandidates(typed = { error("typed") }, bonjour = { error("bonjour") },
            sweep = { listOf(PeerAddress("1.2.3.4", "1.2.3.4", PeerPairing.PORT)) })
        assertEquals(listOf("1.2.3.4"), look().map { it.host })
    }
}
