package com.hubidubitask.mobile.importer

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.ItemSink
import com.hubidubitask.core.agent.ItemsContext
import com.hubidubitask.core.net.SafeFetch
import okhttp3.Dns
import java.io.File
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rules every import source keeps, whoever writes the next one. */
class ImportSourcesTest {

    /** Items in memory, as an import sees the app. */
    class Sink : ItemSink {
        val records = LinkedHashMap<String, EntityRecord>()
        override fun get(id: String) = records[id]
        override fun put(record: EntityRecord) { records[record.id] = record }
        override fun byType(type: String) = records.values.filter { it.type == type }
    }

    /** A fetcher that lets nothing out: every name is refused before any connection. */
    private val shut = SafeFetch({ false }, object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("93.184.216.34"))
    }, {})

    private fun sample(name: String) = when (val input = ImportSources.of(name)!!.input) {
        is ImportInput.File -> ImportValue.File("sample", "x".toByteArray())
        is ImportInput.Secret -> ImportValue.Text("a-token")
        is ImportInput.Address -> ImportValue.Text("https://example.com/calendar.ics")
    }

    @Test fun `every source has its own id, a name and something under it`() {
        val ids = ImportSources.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "ids repeat: $ids")
        for (s in ImportSources.all) {
            assertTrue(s.title.isNotBlank(), s.id)
            assertTrue(s.subtitle.isNotBlank(), s.id)
        }
    }

    @Test fun `a source that reaches the web goes only through the fetcher it is given`() {
        for (s in ImportSources.all.filter { it.input !is ImportInput.File }) {
            assertFailsWith<SafeFetch.Refused>(s.id) { s.read(sample(s.id), ImportContext(Sink(), shut)) }
        }
    }

    @Test fun `no importer or feed code opens a connection of its own`() {
        val roots = listOf("src/main/kotlin/com/hubidubitask/mobile/importer", "src/main/kotlin/com/hubidubitask/core/feed")
        val own = Regex("""OkHttpClient|HttpURLConnection|openConnection|java\.net\.URL\(""")
        val found = roots.flatMap { File(it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(found.size > 5, "the scan found only ${found.size} files")
        for (f in found) assertTrue(!own.containsMatchIn(f.readText()), "${f.path} opens a connection itself")
    }

    @Test fun `everything an import writes is marked, and the agent is told`() {
        val sink = Sink()
        val plan = ImportPlan(
            newProjects = listOf("Garden"),
            items = listOf(
                ImportItem("1", "Plant bulbs", "Garden", "2026-11-02"),
                ImportItem("2", "Buy the good ones", "Garden", null, parentKey = "1"),
                ImportItem("3", "Call the plumber", null, null, body = "after 5"),
            ),
            events = listOf(Ics.Occurrence("u1", "Dentist", "2026-11-03", null, true, null)),
        )
        val r = ImportWriter.apply(plan, sink)
        assertEquals(ImportWriter.Result(tasks = 2, notes = 1, projects = 1, events = 1), r)
        val written = sink.records.values.filter { it.type != "event" }
        assertEquals(4, written.size)
        for (rec in written) {
            assertEquals("import", rec.fields["origin"], rec.toString())
            assertNotNull(ItemsContext.origin(rec), rec.toString())
        }
        val note = written.single { it.type == "note" }
        assertEquals(written.single { it.fields["text"] == "Plant bulbs" }.id, note.fields["parent"])
        assertTrue("(imported from another app)" in ItemsContext.render(sink.records.values.toList(),
            java.time.LocalDate.parse("2026-11-01")))
    }

    @Test fun `what is already here is not written twice`() {
        val sink = Sink()
        val plan = ImportPlan(newProjects = listOf("Garden"),
            events = listOf(Ics.Occurrence("u1", "Dentist", "2026-11-03", null, true, null)))
        ImportWriter.apply(plan, sink)
        assertEquals(ImportWriter.Result(0, 0, 0, 0), ImportWriter.apply(plan, sink))
        assertEquals(2, sink.records.size)
    }
}
