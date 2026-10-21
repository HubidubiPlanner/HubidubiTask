package com.hubidubitask.core.feed

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.Message
import com.hubidubitask.core.feed.kinds.WebPage
import com.hubidubitask.core.net.SafeFetch
import com.hubidubitask.mobile.importer.ImportSourcesTest
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules every feed kind keeps, whoever writes the next one. */
class FeedKindsTest {
    private val day = LocalDate.of(2026, 10, 6)

    /** A fetcher that lets nothing out: every name is refused before any connection. */
    private val shut = SafeFetch({ false }, object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("93.184.216.34"))
    }, {})

    private fun feed(kind: FeedKind, vararg f: Pair<String, String?>) = EntityRecord("f_1", Feeds.TYPE,
        "2026-10-01T00:00:00Z", mapOf("kind" to kind.id, "url" to "https://example.org/x", "period" to "day",
            "model" to "claude") + f)

    @Test fun `every kind has its own id, a name and an address field`() {
        val ids = FeedKinds.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "ids repeat: $ids")
        for (k in FeedKinds.all) {
            assertTrue(k.label.isNotBlank(), k.id)
            assertTrue(k.fields.any { it.key == "url" }, "${k.id} keeps its address as url")
            assertTrue(k.system.contains("NONE"), "${k.id} lets the model call a change noise")
        }
        assertEquals(WebPage, FeedKinds.of(null))
        assertNull(FeedKinds.of("from-a-newer-app"))
    }

    @Test fun `every kind reads only through the fetcher it is given`() {
        for (k in FeedKinds.all)
            assertFailsWith<SafeFetch.Refused>(k.id) { k.read(feed(k), shut) }
    }

    @Test fun `a new feed keeps what was typed and is written by Claude`() {
        val f = Feeds.newFields(WebPage, mapOf("url" to " https://example.org/a ", "title" to " "), "week", null)
        assertEquals(mapOf("url" to "https://example.org/a", "title" to null, "kind" to "web",
            "period" to "week", "model" to "claude", "project" to null), f)
    }

    @Test fun `a feed's task is marked as written from someone else's words`() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("feed.test").build()
        val server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            val web = SafeFetch({ it.isLoopbackAddress }, object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }, { sslSocketFactory(trust.sslSocketFactory(), trust.trustManager) })
            server.enqueue(MockResponse().setBody("<html><body><p>New line</p></body></html>"))
            val sink = ImportSourcesTest.Sink()
            sink.put(feed(WebPage, "url" to "https://feed.test:${server.port}/", "snapshot" to "Old line", "hash" to "x"))
            val asked = ArrayList<List<Message>>()
            val shown = FeedRunner.run(sink, null, day, web) { asked += it; "Read the new line" }
            assertEquals(1, shown.size)
            val task = sink.records.values.single { it.type == "next_action" }
            assertEquals("Read the new line", task.fields["text"])
            assertEquals("feed", task.fields["origin"])
            assertTrue(asked.single().last().content.contains("New line"))
        } finally { server.shutdown() }
    }

    @Test fun `feeds are only ever asked through Claude`() {
        val c = FeedRunner.feedConfig("sk-test")
        assertEquals("claude", c.protocol)
        assertTrue(com.hubidubitask.core.agent.Agent.clientFor(c) is com.hubidubitask.core.agent.ClaudeClient)
    }
}
