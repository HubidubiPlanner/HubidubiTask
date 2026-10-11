package com.hubidubitask.core.agent

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Where the user's own agent listens, as typed in Settings: an IP (or a name)
 * and a port. Neither app runs it; it is whatever the user already runs at
 * home (see [AgentKind]).
 */
object AgentLink {

    /** The host and the port from what was typed; "ip:port" in the IP box works too. Null without a host. */
    fun parse(host: String, port: String): Pair<String, Int?>? {
        var h = host.trim().substringAfter("://").substringBefore('/').trim()
        var p = port.trim()
        Regex("^([^:\\[\\]]+):(\\d+)$").matchEntire(h)?.let { h = it.groupValues[1]; p = it.groupValues[2] }
        Regex("^\\[(.+)]:(\\d+)$").matchEntire(h)?.let { h = it.groupValues[1]; p = it.groupValues[2] }
        h = h.removePrefix("[").removeSuffix("]")
        if (h.isBlank()) return null
        return h to p.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /** "scheme://host:port", IPv6 in brackets; without a port, just "scheme://host". */
    fun endpoint(scheme: String, host: String, port: Int?): String {
        val h = if (':' in host) "[$host]" else host
        return if (port == null) "$scheme://$h" else "$scheme://$h:$port"
    }

    /** The host and the port of a saved endpoint, for the settings form; "" for what it lacks. */
    fun split(endpoint: String?): Pair<String, String> {
        val hp = endpoint.orEmpty().trim().substringAfter("://").substringBefore('/')
        if (hp.isBlank()) return "" to ""
        if (hp.startsWith("[")) return hp.substringAfter('[').substringBefore(']') to hp.substringAfter("]:", "")
        return if (hp.count { it == ':' } == 1) hp.substringBefore(':') to hp.substringAfter(':') else hp to ""
    }

    /**
     * Whether [host] is in a home network: a private, link-local or loopback
     * address, Tailscale's 100.64/10, or a unique-local IPv6 one. A name is
     * resolved, so call this off the main thread. Only such hosts are ever
     * spoken to without TLS.
     */
    fun isPrivate(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).all { a ->
            a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
                (a is Inet4Address && a.address[0].toInt() and 0xff == 100 && a.address[1].toInt() and 0xc0 == 64) ||
                (a is Inet6Address && a.address[0].toInt() and 0xfe == 0xfc)
        }
    }.getOrDefault(false)
}
