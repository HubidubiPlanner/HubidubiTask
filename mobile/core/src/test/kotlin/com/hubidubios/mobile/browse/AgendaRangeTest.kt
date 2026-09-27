package com.hubidubios.mobile.browse

import com.hubidubios.core.EntityRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class AgendaRangeTest {

    // A Saturday: the case that made the ranges rolling rather than calendar weeks.
    private val today = "2026-09-26"

    private fun item(id: String, due: String) =
        EntityRecord(id, "next_action", "2026-09-21T10:00:00Z", mapOf("text" to id, "due" to due))

    private val records = listOf(
        item("late", "2026-09-22"), item("now", "2026-09-26"), item("mon", "2026-09-28"),
        item("fri", "2026-10-02"), item("tue", "2026-10-06"), item("far", "2026-10-20"),
        item("beyond", "2026-10-30"),
    )

    private fun titles(r: Agenda.Range) = Agenda.view(records, today, r).groups.map { it.title }

    @Test
    fun `day shows overdue and today, and counts the rest of the week`() {
        val v = Agenda.view(records, today, Agenda.Range.DAY)
        assertEquals(listOf("Overdue", "Today"), v.groups.map { it.title })
        assertEquals(2, v.laterThisWeek)   // mon, fri
    }

    @Test
    fun `week is the next seven days, one group per day with items`() {
        assertEquals(listOf("Overdue", "Today · Sat 26", "Mon 28", "Fri 2 Oct"), titles(Agenda.Range.WEEK))
    }

    @Test
    fun `month is thirty days grouped by week`() {
        assertEquals(
            // Rolling from Saturday: 26 Sep–2 Oct, 3–9 Oct, 10–16, 17–23 Oct.
            listOf("Overdue", "This week", "Next week · 3–9 Oct", "17–23 Oct"),
            titles(Agenda.Range.MONTH),
        )
    }

    @Test
    fun `overdue shows in every range`() {
        Agenda.Range.entries.forEach {
            assertEquals("Overdue", Agenda.view(records, today, it).groups.first().title)
        }
    }

    @Test
    fun `strip counts items per day`() {
        val c = Agenda.counts(records, today)
        assertEquals(7, c.size)
        assertEquals(listOf(1, 0, 1, 0, 0, 0, 1), c.map { it.second })
    }
}
