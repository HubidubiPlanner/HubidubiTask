package com.hubidubios.mobile.browse

import com.hubidubios.core.EntityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTest {

    private val today = "2026-09-21"

    private fun item(id: String, due: String?) =
        EntityRecord(id, "next_action", "2026-09-21T10:00:00Z",
            buildMap { put("text", id); if (due != null) put("due", due) })

    @Test
    fun `groups into overdue, today, tomorrow and later`() {
        val records = listOf(
            item("late", "2026-09-20"), item("now", "2026-09-21"),
            item("soon", "2026-09-22"), item("far", "2026-10-05"),
        )

        val titles = Agenda.sections(records, today).map { it.title }

        assertEquals(listOf("Overdue", "Today", "Tomorrow", "Later"), titles)
    }

    @Test
    fun `empty buckets are omitted`() {
        // An empty "Tomorrow" heading is furniture, not information.
        val sections = Agenda.sections(listOf(item("now", today)), today)

        assertEquals(listOf("Today"), sections.map { it.title })
    }

    @Test
    fun `overdue is kept apart from today`() {
        val sections = Agenda.sections(
            listOf(item("late", "2026-09-01"), item("now", today)), today)

        assertEquals("Overdue", sections[0].title)
        assertEquals(listOf("late"), sections[0].items.map { it.id })
    }

    @Test
    fun `items inside a section are soonest first`() {
        val records = listOf(item("b", "2026-10-05"), item("a", "2026-09-30"))

        val later = Agenda.sections(records, today).single()

        assertEquals(listOf("a", "b"), later.items.map { it.id })
    }

    @Test
    fun `undated items never appear`() {
        assertTrue(Agenda.sections(listOf(item("none", null)), today).isEmpty())
    }

    @Test
    fun `tomorrow rolls over a month end`() {
        assertEquals("2026-10-01", Agenda.dayAfter("2026-09-30"))
    }

    @Test
    fun `tomorrow rolls over a year end`() {
        assertEquals("2027-01-01", Agenda.dayAfter("2026-12-31"))
    }

    @Test
    fun `tomorrow handles a leap day`() {
        assertEquals("2028-02-29", Agenda.dayAfter("2028-02-28"))
    }
}
