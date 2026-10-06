package com.hubidubitask.core.feed

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.Message
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedsTest {
    private val day = LocalDate.of(2026, 10, 6)   // a Tuesday, ISO week 41
    private fun feed(vararg f: Pair<String, String?>) = EntityRecord("f_1", Feeds.TYPE, "2026-10-01T00:00:00Z",
        mapOf("kind" to "web", "url" to "https://example.org/news", "period" to "week",
            "model" to "claude") + f)

    private fun page(vararg lines: String) =
        "<html><head><title>Example news</title><style>p{}</style></head><body>" +
            lines.joinToString("") { "<p>$it</p>" } + "<script>var x=1</script></body></html>"

    private var asked: List<Message>? = null
    private fun run(f: EntityRecord, html: String, reply: String = "Read the new pricing page",
                    exists: Boolean = false) =
        Feeds.check(f, day, "2026-10-06T08:00:00Z", { html }, { asked = it; reply }, { exists })

    @Test fun `page text keeps blocks as lines and drops scripts, styles and markup`() {
        val text = PageText.extract(page("Prices &amp; plans", "New: Pro <b>tier</b>"))
        assertEquals("Prices & plans\nNew: Pro tier", text)
        assertEquals("Example news", PageText.title(page("x")))
    }

    @Test fun `periods are a day, an ISO week and a month`() {
        assertEquals("2026-10-06", Feeds.periodKey("day", day))
        assertEquals("2026-W41", Feeds.periodKey("week", day))
        assertEquals("2026-10", Feeds.periodKey("month", day))
        assertTrue(Feeds.isDue(feed(), day))
        assertFalse(Feeds.isDue(feed("last_period" to "2026-W41"), day))
    }

    @Test fun `the task id is the same on every device for one feed and period`() {
        assertEquals(Feeds.taskId("f_1", "2026-W41"), Feeds.taskId("f_1", "2026-W41"))
        assertNotEquals(Feeds.taskId("f_1", "2026-W41"), Feeds.taskId("f_1", "2026-W42"))
        assertTrue(Feeds.taskId("f_1", "2026-W41").matches(Regex("t_[a-z2-7]{8}")))
    }

    @Test fun `the first look only remembers the page`() {
        val out = run(feed(), page("One", "Two"))
        assertNull(out.task); assertNull(asked)
        assertEquals("One\nTwo", out.feed.fields["snapshot"])
        assertEquals("2026-W41", out.feed.fields["last_period"])
        assertEquals("Example news", out.feed.fields["title"])
    }

    @Test fun `an unchanged page closes the period without asking the model`() {
        val first = run(feed(), page("One")).feed
        val out = run(first.copy(fields = first.fields - "last_period"), page("One"))
        assertNull(out.task); assertNull(asked)
        assertEquals("No change", out.message)
    }

    @Test fun `a change becomes one task with only the changed lines shown to the model`() {
        val first = run(feed("project" to "p_trip"), page("Prices", "Basic 5 EUR")).feed
        val out = run(first.copy(fields = first.fields - "last_period"), page("Prices", "Basic 7 EUR"))
        val task = out.task!!
        assertEquals("Read the new pricing page", task.text)
        assertEquals(Feeds.taskId("f_1", "2026-W41"), task.id)
        assertEquals("p_trip", task.project)
        assertEquals("2026-10-06", task.due)
        assertTrue(task.body.contains("https://example.org/news"))
        val user = asked!!.last().content
        assertTrue(user.contains("Disappeared:\nBasic 5 EUR")); assertTrue(user.contains("Appeared:\nBasic 7 EUR"))
        assertFalse(user.contains("Prices\n"))
        assertEquals("Basic 7 EUR", out.feed.fields["snapshot"]!!.lines().last())
    }

    @Test fun `noise is not a task, and the page is remembered`() {
        val first = run(feed(), page("Updated 5 Oct")).feed
        val out = run(first.copy(fields = first.fields - "last_period"), page("Updated 6 Oct"), reply = "NONE")
        assertNull(out.task)
        assertEquals("Updated 6 Oct", out.feed.fields["snapshot"])
    }

    @Test fun `a second change in a period that has its task waits for the next period`() {
        val first = run(feed(), page("A")).feed
        val out = run(first.copy(fields = first.fields - "last_period"), page("B"), exists = true)
        assertNull(out.task); assertNull(asked)
        assertEquals("A", out.feed.fields["snapshot"])
    }

    @Test fun `a page that can't be opened leaves the period open for the next try`() {
        val out = Feeds.check(feed(), day, "now", { throw java.io.IOException("timeout") }, { "x" }, { false })
        assertNull(out.task)
        assertNull(out.feed.fields["last_period"])
        assertTrue(out.feed.fields["last_error"]!!.contains("timeout"))
    }

    @Test fun `the reply's first real line is the task`() {
        assertEquals("Book the earlier train", Feeds.firstLine("<think>hm</think>\n- \"Book the earlier train\"\nmore"))
        assertNull(Feeds.firstLine("NONE."))
    }
}
