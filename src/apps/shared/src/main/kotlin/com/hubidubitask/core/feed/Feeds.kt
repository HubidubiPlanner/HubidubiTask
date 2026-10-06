package com.hubidubitask.core.feed

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.Message
import com.hubidubitask.core.agent.Text
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * A feed: something the user watches, turned into at most one task per period.
 *
 * Stored as an EntityRecord of type "feed", synced between the phone and the
 * Mac like any item. Its fields:
 *
 *  kind         "web" (a web page; the only kind so far)
 *  url, title   what is watched and what it is called
 *  period       "day" | "week" | "month": how often it is looked at
 *  model        "claude" | "harness": who writes the task
 *  project      the project the task goes into; absent means the inbox
 *  snapshot     the page's text at the last look, and hash its SHA-256
 *  last_period  the period last looked at ("2026-10-06", "2026-W41", "2026-10")
 *  last_checked when that was; last_changed the day a change was last seen
 *  last_task    the id of the last task made; last_error why the last look failed
 */
object Feeds {
    const val TYPE = "feed"
    const val KIND_WEB = "web"
    val PERIODS = listOf("day" to "Day", "week" to "Week", "month" to "Month")
    val KINDS = listOf(KIND_WEB to "A web page")

    /** Text kept from a page: enough for any page people watch, small enough to sync. */
    const val SNAPSHOT_LIMIT = 40_000
    /** How much of a change the model is shown, per side. */
    private const val CHANGE_LIMIT = 6_000

    fun periodKey(period: String?, day: LocalDate): String = when (period) {
        "week" -> "%d-W%02d".format(day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        "month" -> "%d-%02d".format(day.year, day.monthValue)
        else -> day.toString()
    }

    fun isDue(feed: EntityRecord, today: LocalDate): Boolean =
        !feed.deleted && feed.fields["last_period"] != periodKey(feed.fields["period"], today)

    /**
     * The id of a feed's task for one period. The same on every device, so a
     * phone and a Mac that both look in the same period make one task, not two.
     */
    fun taskId(feedId: String, periodKey: String): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val d = MessageDigest.getInstance("SHA-256").digest("$feedId|$periodKey".toByteArray())
        return "t_" + (0 until 8).map { alphabet[d[it].toInt() and 31] }.joinToString("")
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun label(feed: EntityRecord): String =
        feed.fields["title"]?.takeIf { it.isNotBlank() }
            ?: feed.fields["url"]?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
            ?: feed.id

    /** A task to make: [id] is fixed per feed and period; [project] null means the inbox. */
    data class NewTask(val id: String, val text: String, val project: String?, val due: String,
                       val body: String)

    /** What one look at a feed did: the feed as it now stands, the task if one is due, a line for the UI. */
    data class Outcome(val feed: EntityRecord, val task: NewTask?, val message: String)

    /**
     * Look at [feed] once. Nothing here touches the network or a store: the
     * page comes from [fetch] and the task's wording from [ask], so the rules
     * can be tested on their own. The caller saves [Outcome.feed] and makes
     * [Outcome.task].
     *
     * - The first look only remembers the page.
     * - No change: the period is marked as looked at, no task.
     * - A change: the model turns it into one task, unless it calls the change
     *   noise. If this period already has its task ([taskExists]), the change
     *   is left for the next period rather than swallowed.
     * - A failure (page or model unreachable) records why and leaves the period
     *   open, so the next run tries again.
     */
    fun check(feed: EntityRecord, today: LocalDate, now: String, fetch: (String) -> String,
              ask: (List<Message>) -> String, taskExists: (String) -> Boolean): Outcome {
        val f = feed.fields
        val url = f["url"].orEmpty()
        val key = periodKey(f["period"], today)
        fun save(vararg changes: Pair<String, String?>) = feed.copy(updated = now,
            fields = (f + mapOf("last_checked" to now) + changes.toMap()).filterValues { it != null })

        val html = try { fetch(url) } catch (e: Exception) {
            return Outcome(save("last_error" to "Could not open the page: ${e.message ?: e.javaClass.simpleName}"),
                null, "Could not open the page")
        }
        val text = PageText.extract(html).take(SNAPSHOT_LIMIT)
        val title = f["title"]?.takeIf { it.isNotBlank() } ?: PageText.title(html)
        val sum = hash(text)
        val previous = f["snapshot"]

        if (previous == null) return Outcome(
            save("snapshot" to text, "hash" to sum, "last_period" to key, "title" to title, "last_error" to null),
            null, "Watching from now on")
        if (sum == f["hash"]) return Outcome(
            save("last_period" to key, "title" to title, "last_error" to null), null, "No change")

        val id = taskId(feed.id, key)
        if (taskExists(id)) return Outcome(
            save("last_period" to key, "last_error" to null), null,
            "Changed again; this ${f["period"] ?: "day"}'s task is already made")

        val changes = LineChanges.between(previous, text)
        val reply = try { ask(prompt(title ?: url, url, changes)) } catch (e: Exception) {
            return Outcome(save("last_error" to (e.message ?: "The model did not answer")), null,
                "The model did not answer")
        }
        val line = firstLine(reply)
        val changed = save("snapshot" to text, "hash" to sum, "last_period" to key, "title" to title,
            "last_changed" to today.toString(), "last_error" to null)
        if (line == null) return Outcome(changed, null, "Changed, nothing to do")
        return Outcome(changed + ("last_task" to id),
            NewTask(id, line, f["project"]?.takeIf { it.isNotBlank() }, today.toString(),
                "From the feed ${title ?: url}: $url"),
            "Made a task")
    }

    private operator fun EntityRecord.plus(field: Pair<String, String?>) =
        copy(fields = fields + field)

    private const val SYSTEM =
        "You watch a web page for the user and turn what changed on it into one task. " +
        "You are given the lines that disappeared and the lines that appeared since the last look. " +
        "Reply with one line only: a task the user can act on, starting with a verb, under 100 " +
        "characters, that says what changed. Write it in the language of the page. " +
        "If the change is noise (dates, counters, ads, cookie banners, layout), reply with the single word NONE."

    fun prompt(title: String, url: String, changes: LineChanges): List<Message> {
        fun block(lines: List<String>) =
            lines.joinToString("\n").take(CHANGE_LIMIT).ifBlank { "(none)" }
        return listOf(
            Message("system", SYSTEM),
            Message("user", "Page: $title ($url)\n\nDisappeared:\n${block(changes.removed)}\n\n" +
                "Appeared:\n${block(changes.added)}"),
        )
    }

    /** The task in a reply, or null when the model called the change noise. */
    fun firstLine(reply: String): String? {
        val line = Text.stripReasoning(reply).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.trimStart('-', '*', '•', ' ')?.trim('"', '“', '”', ' ')?.removePrefix("Task:")?.trim()
        return line?.takeIf { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) && it.trimEnd('.') != "NONE" }
            ?.take(200)
    }
}
