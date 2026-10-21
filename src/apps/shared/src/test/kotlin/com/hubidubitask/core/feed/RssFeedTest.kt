package com.hubidubitask.core.feed

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.Message
import com.hubidubitask.core.feed.kinds.RssFeed
import com.hubidubitask.core.net.SafeFetch
import com.hubidubitask.mobile.importer.ImportSourcesTest
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.net.InetAddress
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RssFeedTest {
    private val day = LocalDate.of(2026, 10, 6)

    /** An invented bakery's RSS 2.0 journal; [built] and [dated] are what changes on every fetch. */
    private fun rss(built: String = "Mon, 05 Oct 2026 07:00:00 GMT", dated: String = "Mon, 05 Oct 2026 06:30:00 GMT",
                    newest: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
          <channel>
            <title>Lantern Bakery journal</title>
            <link>https://lantern-bakery.example/journal</link>
            <atom:link rel="self" href="https://lantern-bakery.example/journal/feed.xml"/>
            <description>Notes from the ovens</description>
            <lastBuildDate>$built</lastBuildDate>
            $newest
            <item>
              <media:title>Not this one</media:title>
              <title>Rye starter,
                week three</title>
              <link>https://lantern-bakery.example/journal/rye-starter?utm_source=rss&amp;utm_medium=feed</link>
              <guid isPermaLink="false">lb-0042</guid>
              <pubDate>$dated</pubDate>
            </item>
            <item>
              <title>Oven temperatures, explained</title>
              <link>https://lantern-bakery.example/journal/oven?page=2&amp;utm_campaign=autumn</link>
              <pubDate>$dated</pubDate>
            </item>
            <item>
              <description>&lt;p&gt;Closed on &lt;b&gt;Monday&lt;/b&gt; for repairs.&lt;/p&gt;</description>
              <guid>https://lantern-bakery.example/journal/closed-monday</guid>
            </item>
          </channel>
        </rss>"""

    private val rssLines = listOf(
        "Rye starter, week three — https://lantern-bakery.example/journal/rye-starter",
        "Oven temperatures, explained — https://lantern-bakery.example/journal/oven?page=2",
        "Closed on Monday for repairs. — https://lantern-bakery.example/journal/closed-monday",
    )

    /** An invented app's Atom release notes. */
    private fun atom(updated: String = "2026-10-05T09:00:00Z") = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Quiet Harbour release notes</title>
          <link rel="self" href="https://quiet-harbour.example/notes.atom"/>
          <id>urn:uuid:5e1d7a52-0000-4000-8000-000000000001</id>
          <updated>$updated</updated>
          <entry>
            <title>Version 2.4: tide tables</title>
            <link rel="edit" href="https://quiet-harbour.example/api/entries/24"/>
            <link rel="alternate" type="text/html" href="https://quiet-harbour.example/notes/2-4"/>
            <id>urn:uuid:5e1d7a52-0000-4000-8000-000000000024</id>
            <updated>$updated</updated>
          </entry>
          <entry>
            <title type="html">&lt;em&gt;Version 2.3&lt;/em&gt; &amp;amp; fixes</title>
            <link href="https://quiet-harbour.example/notes/2-3"/>
            <id>urn:uuid:5e1d7a52-0000-4000-8000-000000000023</id>
            <updated>$updated</updated>
          </entry>
        </feed>"""

    @Test fun `an RSS 2 feed becomes one line per item, title and link, in the feed's order`() {
        val s = RssFeed.snapshot(rss())
        assertEquals(rssLines.joinToString("\n"), s.text)
        assertEquals("Lantern Bakery journal", s.title)
    }

    @Test fun `an Atom feed becomes one line per entry, with the entry's own link`() {
        val s = RssFeed.snapshot(atom())
        assertEquals("Version 2.4: tide tables — https://quiet-harbour.example/notes/2-4\n" +
            "Version 2.3 & fixes — https://quiet-harbour.example/notes/2-3", s.text)
        assertEquals("Quiet Harbour release notes", s.title)
    }

    @Test fun `dates that change on every fetch do not change the snapshot`() {
        assertEquals(RssFeed.snapshot(rss()).text,
            RssFeed.snapshot(rss(built = "Tue, 06 Oct 2026 07:00:00 GMT", dated = "Tue, 06 Oct 2026 06:59:00 GMT")).text)
        assertEquals(RssFeed.snapshot(atom()).text, RssFeed.snapshot(atom(updated = "2026-10-06T11:12:13Z")).text)
    }

    @Test fun `a new item is one new line`() {
        val newer = RssFeed.snapshot(rss(newest = "<item><title>Pumpkin loaves are back</title>" +
            "<link>https://lantern-bakery.example/journal/pumpkin</link></item>")).text
        val changes = LineChanges.between(RssFeed.snapshot(rss()).text, newer)
        assertEquals(listOf("Pumpkin loaves are back — https://lantern-bakery.example/journal/pumpkin"), changes.added)
        assertTrue(changes.removed.isEmpty())
    }

    @Test fun `a feed's DOCTYPE or ENTITY is refused before parsing`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///etc/hosts">]>
            <rss version="2.0"><channel><title>&secret;</title></channel></rss>"""
        val e = assertFailsWith<IOException> { RssFeed.snapshot(xxe) }
        assertTrue("DOCTYPE" in e.message.orEmpty())
        assertFailsWith<IOException> { RssFeed.snapshot("""<rss version="2.0"><!ENTITY a "aaaa"><channel/></rss>""") }
        // And the parser on its own would not take one either.
        assertFailsWith<SAXException> { RssFeed.builder().parse(InputSource(StringReader(xxe))) }
    }

    @Test fun `a web page or broken XML is not a feed`() {
        assertEquals("Not an RSS or Atom feed.",
            assertFailsWith<IOException> { RssFeed.snapshot("<html><body><p>Hi</p><br></body></html>") }.message)
        assertFailsWith<IOException> { RssFeed.snapshot("<rss><channel><title>Cut off") }
        assertFailsWith<IOException> { RssFeed.snapshot("""<feed><title>No Atom namespace</title></feed>""") }
    }

    @Test fun `a long feed is cut to the newest entries the feed lists first`() {
        val items = (1..150).joinToString("") { "<item><title>Loaf $it</title></item>" }
        val lines = RssFeed.snapshot("<rss version=\"2.0\"><channel>$items</channel></rss>").text.lines()
        assertEquals(RssFeed.MAX_ENTRIES, lines.size)
        assertEquals("Loaf 1", lines.first())
    }

    @Test fun `only a web address is enough to watch`() {
        assertTrue(RssFeed.valid(mapOf("url" to " https://lantern-bakery.example/feed.xml ")))
        assertFalse(RssFeed.valid(mapOf("url" to "ftp://lantern-bakery.example/feed.xml")))
        assertFalse(RssFeed.valid(mapOf("url" to "")))
    }

    @Test fun `a new entry in a watched feed becomes one task, written from the entries`() {
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
            val url = "https://feed.test:${server.port}/journal/feed.xml"
            val sink = ImportSourcesTest.Sink()
            sink.put(EntityRecord("f_1", Feeds.TYPE, "2026-10-01T00:00:00Z",
                Feeds.newFields(RssFeed, mapOf("url" to url), "day", null).filterValues { it != null }))
            val asked = ArrayList<List<Message>>()
            val ask = { m: List<Message> -> asked += m; "Read the pumpkin loaves post" }

            // The first look only remembers the entries, and takes the feed's own name.
            server.enqueue(MockResponse().setBody(rss()))
            assertEquals(mapOf("f_1" to "Watching from now on"), FeedRunner.run(sink, null, day, web, ask))
            assertEquals("Lantern Bakery journal", sink.get("f_1")!!.fields["title"])
            assertTrue(asked.isEmpty())

            // The next day one item is new and the dates moved: the model sees only the new item.
            server.enqueue(MockResponse().setBody(rss(built = "Tue, 06 Oct 2026 07:00:00 GMT",
                newest = "<item><title>Pumpkin loaves are back</title>" +
                    "<link>https://lantern-bakery.example/journal/pumpkin?fbclid=abc</link></item>")))
            assertEquals(mapOf("f_1" to "Made a task"), FeedRunner.run(sink, null, day.plusDays(1), web, ask))
            val task = sink.records.values.single { it.type == "next_action" }
            assertEquals("Read the pumpkin loaves post", task.fields["text"])
            assertEquals("feed", task.fields["origin"])
            val (system, user) = asked.single()
            assertEquals(RssFeed.system, system.content)
            assertEquals("Feed: Lantern Bakery journal ($url)\n\nDisappeared:\n(none)\n\n" +
                "Appeared:\nPumpkin loaves are back — https://lantern-bakery.example/journal/pumpkin", user.content)
            assertNull(sink.get("f_1")!!.fields["last_error"])
        } finally { server.shutdown() }
    }
}
