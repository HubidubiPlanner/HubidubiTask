package com.hubidubitask.mobile.browse

import com.hubidubitask.core.EntityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewBrowseTest {

    private fun rec(id: String, type: String, updated: String,
                    title: String? = null, text: String? = null, body: String? = null) =
        EntityRecord(id, type, updated, buildMap {
            if (title != null) put("title", title)
            if (text != null) put("text", text)
            if (body != null) put("body", body)
        })

    private val inbox = listOf(rec("i_1", "inbox_item", "2026-09-01T00:00:00Z", text = "call plumber"))
    private val actions = listOf(rec("t_1", "next_action", "2026-09-05T00:00:00Z", text = "email Bob"))
    private val notes = listOf(rec("n_1", "note", "2026-09-09T00:00:00Z", title = "Roof quote",
        body = "Gutter replacement estimate"))

    @Test
    fun `items covers captures and actions, notes covers notes`() {
        assertEquals(listOf("i_1", "t_1"),
            ReviewBrowse.pool(ReviewBrowse.Kind.ITEMS, inbox, actions, notes).map { it.id })
        assertEquals(listOf("n_1"),
            ReviewBrowse.pool(ReviewBrowse.Kind.NOTES, inbox, actions, notes).map { it.id })
    }

    @Test
    fun `recent is newest first and capped`() {
        val many = (1..10).map { rec("t_$it", "next_action", "2026-09-%02dT00:00:00Z".format(it)) }

        val out = ReviewBrowse.recent(many, limit = 5)

        assertEquals(5, out.size)
        assertEquals(listOf("t_10", "t_9", "t_8", "t_7", "t_6"), out.map { it.id })
    }

    @Test
    fun `with no query the screen shows recent activity`() {
        val out = ReviewBrowse.results(inbox + actions + notes, query = "  ")

        assertEquals(listOf("n_1", "t_1", "i_1"), out.map { it.id })
    }

    @Test
    fun `search matches the label case-insensitively`() {
        val out = ReviewBrowse.search(inbox + actions, "PLUMB")

        assertEquals(listOf("i_1"), out.map { it.id })
    }

    @Test
    fun `search also matches a note body, not just its title`() {
        val out = ReviewBrowse.search(notes, "gutter")

        assertEquals(listOf("n_1"), out.map { it.id })
    }

    @Test
    fun `search is not limited to the recent cap`() {
        // Typing a query is deliberate: showing only 5 of 8 matches would hide
        // results the user explicitly asked for.
        val many = (1..8).map {
            rec("t_$it", "next_action", "2026-09-%02dT00:00:00Z".format(it), text = "report $it")
        }

        assertEquals(8, ReviewBrowse.search(many, "report").size)
    }

    @Test
    fun `search results are newest first`() {
        val a = rec("t_old", "next_action", "2026-09-01T00:00:00Z", text = "report old")
        val b = rec("t_new", "next_action", "2026-09-30T00:00:00Z", text = "report new")

        assertEquals(listOf("t_new", "t_old"), ReviewBrowse.search(listOf(a, b), "report").map { it.id })
    }

    @Test
    fun `a query matching nothing returns nothing rather than falling back to recent`() {
        val out = ReviewBrowse.results(inbox + actions + notes, "zzzz")

        assertTrue("a failed search must not silently show unrelated items", out.isEmpty())
    }
}
