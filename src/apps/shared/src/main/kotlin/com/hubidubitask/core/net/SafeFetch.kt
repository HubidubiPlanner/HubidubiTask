package com.hubidubitask.core.net

import com.hubidubitask.core.i18n.t
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy

/**
 * Fetches an address someone else controls (a watched page, a calendar feed)
 * so that it can't reach the user's own network or be changed on the way:
 *
 * - https only: http:// and webcal:// are asked for as https://, anything else is refused;
 * - redirects are followed here, at most [MAX_REDIRECTS], and only to https;
 * - public addresses only: a name that resolves to this machine or the home
 *   network is refused, and so is such an address typed as numbers.
 *
 * OkHttp connects only to what [PublicDns] hands it, so a name can't switch
 * to a private address between the check and the connection. No proxy is
 * used, since a proxy would look the name up itself.
 */
class SafeFetch internal constructor(
    private val allowed: (InetAddress) -> Boolean,
    dns: Dns,
    tune: OkHttpClient.Builder.() -> Unit,
) {
    /** A fetcher for the internet; [tune] sets its timeouts. */
    constructor(tune: OkHttpClient.Builder.() -> Unit = {}) : this(::isPublic, Dns.SYSTEM, tune)

    /** Why an address was not fetched; the message is for the user. */
    class Refused(message: String) : IOException(message)

    private val http = OkHttpClient.Builder().apply(tune)
        .followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY).dns(PublicDns(dns, allowed)).build()

    /**
     * Asks for [url] with [headers] and hands the final answer, not a
     * redirect, to [read]. Throws [Refused] for what is not allowed.
     */
    fun <T> get(url: String, headers: Map<String, String> = emptyMap(), read: (Response) -> T): T {
        var at = https(url) ?: throw Refused(t("Not a web address."))
        repeat(MAX_REDIRECTS + 1) {
            checkNumeric(at)
            val req = Request.Builder().url(at).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(req).execute().use { r ->
                at = redirect(at, r.code, r.header("Location")) ?: return read(r)
            }
        }
        throw Refused(t("Too many redirects."))
    }

    /** OkHttp looks up no name for an address typed as numbers, so those are checked here. */
    private fun checkNumeric(url: HttpUrl) {
        if (NUMERIC.matches(url.host) && !allowed(InetAddress.getByName(url.host)))
            throw Refused(notPublic())
    }

    /** Hands OkHttp only the public addresses a name resolves to; none at all is refused. */
    internal class PublicDns(private val dns: Dns, private val allowed: (InetAddress) -> Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            dns.lookup(hostname).filter(allowed).ifEmpty { throw Refused(notPublic()) }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(300, 301, 302, 303, 307, 308)
        /** What OkHttp takes for an address typed as numbers rather than a name. */
        private val NUMERIC = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")

        private fun notPublic() = t("That address is not on the public internet.")

        /** [url] as the https address to ask for, or null when it is not a web address. */
        fun https(url: String): HttpUrl? =
            url.trim().replaceFirst(Regex("^(http|webcal)://", RegexOption.IGNORE_CASE), "https://")
                .toHttpUrlOrNull()?.takeIf { it.isHttps }

        /**
         * Where an answer with [code] and [location] sends [from], or null when
         * it is not a redirect. A relative [location] is read against [from];
         * anything but https is refused.
         */
        fun redirect(from: HttpUrl, code: Int, location: String?): HttpUrl? {
            if (code !in REDIRECTS || location == null) return null
            return from.resolve(location)?.takeIf { it.isHttps }
                ?: throw Refused(t("It redirects to an address that isn't secure."))
        }

        /**
         * Whether [a] is on the public internet: not this machine, the home
         * network (10/8, 172.16/12, 192.168/16, fc00::/7), link-local, the
         * carrier's shared range (100.64/10), multicast, unspecified or reserved.
         * IPv6 forms that carry an IPv4 address are judged by that address.
         */
        fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                a.isSiteLocalAddress || a.isMulticastAddress) return false
            val b = a.address.map { it.toInt() and 0xff }
            if (a is Inet4Address)   // 0/8 "this network", 240/4 reserved and broadcast, 100.64/10
                return !(b[0] == 0 || b[0] >= 240 || (b[0] == 100 && b[1] in 64..127))
            if (a !is Inet6Address) return false
            fun zero(range: IntRange) = range.all { b[it] == 0 }
            val mapped = zero(0..9) && b[10] == 0xff && b[11] == 0xff               // ::ffff:0:0/96
            val nat64 = b.take(4) == listOf(0, 0x64, 0xff, 0x9b) && zero(4..11)     // 64:ff9b::/96
            return when {
                mapped || nat64 -> isPublic(InetAddress.getByAddress(a.address.copyOfRange(12, 16)))
                zero(0..11) -> false                  // ::, ::1 and the old IPv4-compatible form
                else -> (b[0] and 0xfe) != 0xfc       // unique local fc00::/7
            }
        }
    }
}
