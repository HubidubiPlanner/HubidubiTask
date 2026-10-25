package com.hubidubitask.desktop

import com.hubidubitask.core.peer.PeerNet
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the computer announces itself, and which address it offers to type. */
class LanTest {
    private val wifi = Lan.Nic("en0", "192.168.1.62")
    private val vpn = Lan.Nic("utun4", "10.64.0.2", pointToPoint = true)
    private val docker = Lan.Nic("docker0", "172.17.0.1")
    private val wireguard = Lan.Nic("wg0", "10.8.0.2")
    private val tailscale = Lan.Nic("tailscale0", "100.101.1.2", pointToPoint = true)
    /** A Linux bridge that carries the real network (VMs bridged onto the LAN). */
    private val lanBridge = Lan.Nic("br0", "192.168.1.40")

    @Test
    fun `nothing is announced on a VPN, everything else is`() {
        assertEquals(listOf(wifi, docker, lanBridge),
            Lan.announceOn(listOf(vpn, wifi, docker, wireguard, tailscale, lanBridge)))
    }

    @Test
    fun `the address to type is the real network's, even when the VPN is listed first`() {
        assertEquals(listOf(wifi, lanBridge, docker), Lan.forTyping(listOf(vpn, docker, wifi, lanBridge)))
    }

    @Test
    fun `a computer only on a VPN still says where it is`() {
        assertEquals(listOf(tailscale), Lan.forTyping(listOf(tailscale)))
    }

    @Test
    fun `this computer's addresses are home-network ones`() {
        Lan.nics().forEach { assertTrue(PeerNet.isLocal(InetAddress.getByName(it.address)), it.toString()) }
    }
}
