package com.hubidubitask.core.net

import com.hubidubitask.core.feed.PageFetch
import com.hubidubitask.mobile.importer.fetchCalendarText
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeFetchTest {
    private val cert = HeldCertificate.Builder()
        .addSubjectAlternativeName("feed.test").addSubjectAlternativeName("next.test").build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()

    /** The test's names all lead to the local server; any other name is not found. */
    private val names = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.endsWith(".test")) listOf(InetAddress.getByName("127.0.0.1"))
            else throw UnknownHostException(hostname)
    }

    /** The real rules, except that this machine is let through to reach the local server. */
    private val web = SafeFetch({ it.isLoopbackAddress || SafeFetch.isPublic(it) }, names,
        { sslSocketFactory(trust.sslSocketFactory(), trust.trustManager) })

    private fun at(path: String, host: String = "feed.test") = "https://$host:${server.port}$path"
    private fun redirect(code: Int, to: String) = MockResponse().setResponseCode(code).setHeader("Location", to)
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun fixed(vararg ips: String) = object : Dns {
        override fun lookup(hostname: String) = ips.map { ip(it) }
    }

    @AfterTest fun stop() = server.shutdown()

    @Test fun `only addresses on the public internet count as public`() {
        for (a in listOf("8.8.8.8", "93.184.216.34", "100.63.255.255", "100.128.0.1", "172.15.255.255",
                "172.32.0.1", "192.169.0.1", "2606:4700::1111", "64:ff9b::808:808"))
            assertTrue(SafeFetch.isPublic(ip(a)), a)
        for (a in listOf("127.0.0.1", "127.8.9.10", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "0.1.2.3", "224.0.0.251",
                "255.255.255.255", "240.0.0.1", "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "fec0::1",
                "ff02::fb", "::ffff:192.168.1.1", "::ffff:127.0.0.1", "::127.0.0.1", "64:ff9b::a00:1"))
            assertFalse(SafeFetch.isPublic(ip(a)), a)
        // An IPv4-mapped address kept in its IPv6 form is judged by the IPv4 inside it.
        fun mapped(vararg v4: Int) = Inet6Address.getByAddress(null,
            ByteArray(10) + byteArrayOf(-1, -1) + v4.map { it.toByte() }.toByteArray(), -1)
        assertFalse(SafeFetch.isPublic(mapped(192, 168, 1, 1)))
        assertFalse(SafeFetch.isPublic(mapped(127, 0, 0, 1)))
        assertTrue(SafeFetch.isPublic(mapped(8, 8, 8, 8)))
    }

    @Test fun `http and webcal are asked for as https, other schemes not at all`() {
        assertEquals("https://example.org/news", SafeFetch.https("http://example.org/news").toString())
        assertEquals("https://example.org/cal.ics", SafeFetch.https(" webcal://example.org/cal.ics ").toString())
        assertEquals("https://example.org/", SafeFetch.https("HTTP://example.org/").toString())
        assertEquals("https://example.org:8443/x", SafeFetch.https("https://example.org:8443/x").toString())
        for (u in listOf("ftp://example.org/", "file:///etc/passwd", "javascript:alert(1)", "example.org", ""))
            assertNull(SafeFetch.https(u), u)
        val e = assertFailsWith<SafeFetch.Refused> { web.get("ftp://feed.test/") { it.code } }
        assertEquals("Not a web address.", e.message)
    }

    @Test fun `a redirect is read against where it comes from and goes only to https`() {
        val from = "https://example.org/a/b?q=1".toHttpUrl()
        assertEquals("https://example.org/a/c", SafeFetch.redirect(from, 302, "c").toString())
        assertEquals("https://example.org/d", SafeFetch.redirect(from, 301, "/d").toString())
        assertEquals("https://other.example/e", SafeFetch.redirect(from, 307, "//other.example/e").toString())
        assertEquals("https://other.example/f", SafeFetch.redirect(from, 308, "https://other.example/f").toString())
        assertNull(SafeFetch.redirect(from, 200, "https://other.example/"))
        assertNull(SafeFetch.redirect(from, 302, null))
        assertFailsWith<SafeFetch.Refused> { SafeFetch.redirect(from, 302, "http://example.org/") }
        assertFailsWith<SafeFetch.Refused> { SafeFetch.redirect(from, 303, "ftp://example.org/") }
    }

    @Test fun `an http address is fetched over https`() {
        server.enqueue(MockResponse().setBody("<p>hello</p>"))
        assertEquals("<p>hello</p>", PageFetch.get("http://feed.test:${server.port}/news", web))
        val req = server.takeRequest()
        assertEquals("https", req.requestUrl!!.scheme)
        assertEquals("/news", req.path)
        assertEquals("Mozilla/5.0 (HubidubiTask feed reader)", req.getHeader("User-Agent"))
    }

    @Test fun `a redirect to http is refused`() {
        server.enqueue(redirect(302, "http://feed.test:${server.port}/plain"))
        val e = assertFailsWith<SafeFetch.Refused> { PageFetch.get(at("/news"), web) }
        assertEquals("It redirects to an address that isn't secure.", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a redirect into the home network is refused before anything connects`() {
        for (to in listOf("https://192.168.1.1/admin", "https://10.0.0.1:${server.port}/",
                "https://[::ffff:192.168.1.1]/", "https://169.254.169.254/latest/meta-data/", "https://[fd00::1]/")) {
            server.enqueue(redirect(302, to))
            val e = assertFailsWith<SafeFetch.Refused>(to) { PageFetch.get(at("/news"), web) }
            assertEquals("That address is not on the public internet.", e.message)
        }
    }

    @Test fun `an address typed as numbers is checked, since no name is looked up for it`() {
        for (u in listOf("https://127.0.0.1:${server.port}/", "http://192.168.1.1/", "https://[::1]:${server.port}/"))
            assertFailsWith<SafeFetch.Refused>(u) { SafeFetch().get(u) { it.code } }
        assertEquals(0, server.requestCount)
    }

    @Test fun `a name that resolves only into the home network is refused`() {
        val router = SafeFetch({ SafeFetch.isPublic(it) }, fixed("192.168.1.1", "10.0.0.7", "::1"), {})
        val e = assertFailsWith<SafeFetch.Refused> { router.get("https://router.example/") { it.code } }
        assertEquals("That address is not on the public internet.", e.message)
        // A name with public and private addresses keeps only the public ones.
        assertEquals(listOf(ip("93.184.216.34")),
            SafeFetch.PublicDns(fixed("192.168.1.1", "93.184.216.34", "fe80::1"), { SafeFetch.isPublic(it) }).lookup("mixed.example"))
    }

    @Test fun `five redirects are followed, a sixth is not`() {
        repeat(5) { server.enqueue(redirect(302, "/hop$it")) }
        server.enqueue(MockResponse().setBody("arrived"))
        assertEquals("arrived", PageFetch.get(at("/start"), web))
        assertEquals(6, server.requestCount)

        repeat(6) { server.enqueue(redirect(301, "/again$it")) }
        val e = assertFailsWith<SafeFetch.Refused> { PageFetch.get(at("/start"), web) }
        assertEquals("Too many redirects.", e.message)
        assertEquals(12, server.requestCount)
    }

    @Test fun `relative redirects are followed from where they come from`() {
        server.enqueue(redirect(301, "../moved/here?x=1"))
        server.enqueue(redirect(302, "//next.test:${server.port}/elsewhere"))
        server.enqueue(redirect(307, "/final"))
        server.enqueue(MockResponse().setBody("done"))
        assertEquals("done", PageFetch.get(at("/news/today/index.html"), web))
        val asked = List(4) { server.takeRequest() }
        assertEquals(listOf("/news/today/index.html", "/news/moved/here?x=1", "/elsewhere", "/final"), asked.map { it.path })
        assertEquals(listOf("feed", "feed", "next", "next").map { "$it.test:${server.port}" },
            asked.map { it.getHeader(":authority") ?: it.getHeader("Host") })
    }

    @Test fun `a page is read up to 3 MB and no further`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4 * 1024 * 1024) { 'a'.code.toByte() })))
        assertEquals(PageFetch.LIMIT, PageFetch.get(at("/huge"), web).length.toLong())
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals("The page answered 503.", assertFailsWith<IOException> { PageFetch.get(at("/down"), web) }.message)
    }

    @Test fun `a calendar keeps its own checks`() {
        server.enqueue(MockResponse().setBody("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"))
        assertTrue(fetchCalendarText("webcal://feed.test:${server.port}/basic.ics", web).startsWith("BEGIN:VCALENDAR"))
        for ((code, message) in listOf(404 to "The calendar address was not accepted (404).",
                403 to "The calendar address was not accepted (403).", 500 to "The calendar server answered 500.")) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(message, assertFailsWith<IOException> { fetchCalendarText(at("/x.ics"), web) }.message)
        }
        server.enqueue(MockResponse().setBody("<html>not a calendar</html>"))
        assertEquals("That address did not return a calendar.",
            assertFailsWith<IOException> { fetchCalendarText(at("/x.ics"), web) }.message)
    }
}
