package com.hubidubitask.desktop

import com.hubidubitask.core.peer.PeerNet
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The computer's addresses on the home network: where it announces itself
 * by Bonjour, and which address a phone can type.
 *
 * A Mac or a Linux box often has more than its Wi-Fi or Ethernet: a VPN
 * tunnel, the bridges of Docker and virtual machines. No phone is on a
 * tunnel, so nothing is announced there; the addresses to type list the
 * real network first.
 */
object Lan {
    /** One IPv4 address of one network interface. */
    data class Nic(val name: String, val address: String, val pointToPoint: Boolean = false)

    /** VPNs and tunnels: utun, ipsec, ppp on the Mac; tun, tap, wg, tailscale, ZeroTier on Linux. */
    private val tunnelName = Regex("(utun|ipsec|ppp|tun|tap|wg|tailscale|zt|gif|stf)\\w*")
    /** Bridges of containers and virtual machines: on this computer, but no phone is on them. */
    private val bridgeName = Regex("(docker|br-|virbr|veth|vmnet|vboxnet|bridge|lxcbr|lxdbr|cni|podman)[\\w-]*")

    private fun tunnel(n: Nic) = n.pointToPoint || tunnelName.matches(n.name)
    private fun bridge(n: Nic) = bridgeName.matches(n.name)

    /** Where to announce: every interface but tunnels. */
    fun announceOn(nics: List<Nic>): List<Nic> = nics.filterNot(::tunnel)

    /**
     * Addresses to type on the phone, likeliest first: Wi-Fi and Ethernet,
     * then bridges. Tunnels only when there is nothing else (a phone on the
     * same VPN can reach those).
     */
    fun forTyping(nics: List<Nic>): List<Nic> =
        nics.filterNot(::tunnel).sortedBy { if (bridge(it)) 1 else 0 }.ifEmpty { nics }

    /** This computer's IPv4 home-network addresses now: no loopback, no link-local. */
    fun nics(): List<Nic> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { ni ->
                val p2p = runCatching { ni.isPointToPoint }.getOrDefault(false)
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { PeerNet.isLocal(it) && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { Nic(ni.name, it.hostAddress, p2p) }
            }
    }.getOrDefault(emptyList())
}
