package com.hubidubitask.desktop.harness

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.HarnessAction
import com.hubidubitask.desktop.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Harness changed lands in this computer's items as if typed here. */
class ItemsTest {
    private val store = Store(Files.createTempDirectory("items").toFile())
    private var changed = 0
    private val items = Items(store) { changed++ }

    init {
        store.put(EntityRecord("p_web", "project", "2026-10-06T00:00:00Z", mapOf("title" to "Website")))
        store.put(EntityRecord("t_host", "next_action", "2026-10-06T00:00:00Z", mapOf("text" to "Pick a host")))
        store.put(EntityRecord("i_milk", "inbox_item", "2026-10-06T00:00:00Z", mapOf("text" to "milk")))
        store.put(EntityRecord("f_1", "feed", "2026-10-06T00:00:00Z", mapOf("url" to "https://example.com")))
    }

    @Test fun `a question carries every item but the feeds`() {
        assertEquals(setOf("p_web", "t_host", "i_milk"), items.records().map { it.id }.toSet())
    }

    @Test fun `added, captured and done`() {
        items.apply(listOf(
            HarnessAction("add_task", text = "Compare prices", project = "p_web", due = "2026-10-09"),
            HarnessAction("add_task", text = "Loose one", project = "p_gone"),
            HarnessAction("capture", text = "call mum"),
            HarnessAction("mark_done", id = "t_host"),
            HarnessAction("mark_done", id = "i_milk"),
            HarnessAction("add_task"), HarnessAction("unknown", text = "x")))
        val all = store.all()
        val added = all.single { it.fields["text"] == "Compare prices" }
        assertEquals(mapOf("text" to "Compare prices", "project" to "website", "parent" to "p_web",
            "due" to "2026-10-09"), added.fields)
        assertEquals("inbox", all.single { it.fields["text"] == "Loose one" }.fields["project"])
        assertEquals("inbox_item", all.single { it.fields["text"] == "call mum" }.type)
        assertEquals("true", store.get("t_host")!!.fields["done"])
        assertTrue(store.get("i_milk")!!.deleted)
        assertNull(all.firstOrNull { it.fields["text"] == "x" })
        assertEquals(1, changed, "the screens refresh once")
    }
}
