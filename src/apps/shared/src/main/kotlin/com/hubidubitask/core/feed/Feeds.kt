package com.hubidubitask.core.feed

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.Message
import com.hubidubitask.core.agent.Text
import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.feed.kinds.WebPage
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * A feed: something the user watches, turned into at most one task per period.
 *
 * Stored as an EntityRecord of type "feed", synced between the phone and the
 * Mac like any item. Its fields:
 *
 *  kind         which [FeedKind] it is ("web", a web page, when absent)
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
    val PERIODS = listOf("day" to "Day", "week" to "Week", "month" to "Month")

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

    /** A new feed of [kind]: what was typed ([values], by field key), how often, where its task goes. */
    fun newFields(kind: FeedKind, values: Map<String, String>, period: String, project: String?): Map<String, String?> =
        kind.fields.associate { it.key to values[it.key]?.trim()?.ifBlank { null } } +
            mapOf("kind" to kind.id, "period" to period, "model" to "claude", "project" to project)

    /**
     * Look at [feed], a [kind], once. Nothing here touches the network or a
     * store: what it shows comes from [read] and the task's wording from
     * [ask], so the rules can be tested on their own. The caller saves [Outcome.feed] and makes
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
    fun check(feed: EntityRecord, today: LocalDate, now: String, read: (EntityRecord) -> Snapshot,
              ask: (List<Message>) -> String, taskExists: (String) -> Boolean,
              kind: FeedKind = FeedKinds.of(feed.fields["kind"]) ?: WebPage): Outcome {
        val f = feed.fields
        val url = f["url"].orEmpty()
        val key = periodKey(f["period"], today)
        fun save(vararg changes: Pair<String, String?>) = feed.copy(updated = now,
            fields = (f + mapOf("last_checked" to now) + changes.toMap()).filterValues { it != null })

        val seen = try { read(feed) } catch (e: Exception) {
            return Outcome(save("last_error" to t("Could not open the page: %s", e.message ?: e.javaClass.simpleName)),
                null, t("Could not open the page"))
        }
        val text = seen.text.take(SNAPSHOT_LIMIT)
        val title = f["title"]?.takeIf { it.isNotBlank() } ?: seen.title
        val sum = hash(text)
        val previous = f["snapshot"]

        if (previous == null) return Outcome(
            save("snapshot" to text, "hash" to sum, "last_period" to key, "title" to title, "last_error" to null),
            null, t("Watching from now on"))
        if (sum == f["hash"]) return Outcome(
            save("last_period" to key, "title" to title, "last_error" to null), null, t("No change"))

        val id = taskId(feed.id, key)
        if (taskExists(id)) return Outcome(
            save("last_period" to key, "last_error" to null), null,
            when (f["period"]) {
                "week" -> t("Changed again; this week's task is already made")
                "month" -> t("Changed again; this month's task is already made")
                else -> t("Changed again; this day's task is already made")
            })

        val changes = LineChanges.between(previous, text)
        val reply = try { ask(prompt(title ?: url, url, changes, kind)) } catch (e: Exception) {
            return Outcome(save("last_error" to (e.message ?: t("The model did not answer"))), null,
                t("The model did not answer"))
        }
        val line = firstLine(reply)
        val changed = save("snapshot" to text, "hash" to sum, "last_period" to key, "title" to title,
            "last_changed" to today.toString(), "last_error" to null)
        if (line == null) return Outcome(changed, null, t("Changed, nothing to do"))
        return Outcome(changed + ("last_task" to id),
            NewTask(id, line, f["project"]?.takeIf { it.isNotBlank() }, today.toString(),
                t("From the feed %s: %s", title ?: url, url)),
            t("Made a task"))
    }

    private operator fun EntityRecord.plus(field: Pair<String, String?>) =
        copy(fields = fields + field)

    fun prompt(title: String, url: String, changes: LineChanges, kind: FeedKind = WebPage): List<Message> {
        fun block(lines: List<String>) =
            lines.joinToString("\n").take(CHANGE_LIMIT).ifBlank { "(none)" }
        return listOf(
            Message("system", kind.system),
            Message("user", "${kind.noun}: $title ($url)\n\nDisappeared:\n${block(changes.removed)}\n\n" +
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
