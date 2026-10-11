package com.hubidubitask.core.agent

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.feed.Feeds
import com.hubidubitask.mobile.tree.ProjectTree
import java.time.LocalDate

/**
 * The user's items as compact text, for an agent that has no other way to see
 * them: the inbox, each project with its open tasks and notes, the calendar
 * ahead, and loose notes. Every line carries the item's id, so a change can
 * name it. Done tasks, deleted items and feeds stay out. Too long for [limit]:
 * bodies go first, then notes, then most of the calendar, and what still does
 * not fit is counted rather than cut mid-line.
 */
object ItemsContext {
    const val LIMIT = 24_000

    fun render(records: List<EntityRecord>, today: LocalDate, limit: Int = LIMIT): String {
        val v = View(records)
        for ((bodies, notes, days) in listOf(Triple(true, true, 30L), Triple(false, true, 30L),
                Triple(false, false, 30L), Triple(false, false, 7L))) {
            val text = v.text(today, bodies, notes, days)
            if (text.length <= limit) return text
        }
        val lines = v.text(today, false, false, 7L).lines()
        val kept = StringBuilder()
        var i = 0
        while (i < lines.size && kept.length + lines[i].length + 1 <= limit - 40) { kept.append(lines[i]).append('\n'); i++ }
        val rest = lines.drop(i).count { it.startsWith("[") || it.startsWith("- ") }
        return kept.toString() + "- … $rest more not shown"
    }

    private class View(records: List<EntityRecord>) {
        val live = records.filter { !it.deleted && it.type != Feeds.TYPE }
        val byId = live.associateBy { it.id }
        val slugs = live.filter { it.type == "project" }
            .associate { ProjectTree.slugOf(title(it)) to it.id }

        fun f(r: EntityRecord, k: String) = r.fields[k].orEmpty()
        fun title(r: EntityRecord) = r.fields["title"] ?: r.fields["text"] ?: r.fields["summary"] ?: r.id
        fun isTask(r: EntityRecord) = r.type == "next_action" || r.type == "inbox_item"
        fun open(r: EntityRecord) = f(r, "done") != "true"

        /** The project an item sits in, up through notes on tasks; null at the top. */
        fun projectOf(r: EntityRecord): EntityRecord? {
            var p = ProjectTree.parentOf(r, slugs)?.let(byId::get)
            repeat(8) {
                if (p == null || p!!.type == "project") return p
                p = f(p!!, "parent").takeIf { it.isNotBlank() }?.let(byId::get)
            }
            return null
        }

        fun line(r: EntityRecord, bodies: Boolean): String {
            val kind = when (r.type) { "next_action" -> "task"; "inbox_item" -> "inbox"; else -> r.type }
            val parts = mutableListOf("[$kind] ${title(r)}")
            if (f(r, "due").isNotBlank()) parts += "due " + f(r, "due")
            if (bodies && f(r, "body").isNotBlank()) parts += "— " + f(r, "body").replace('\n', ' ').take(200)
            parts += "(id ${r.id})"
            return parts.joinToString(" · ")
        }

        fun text(today: LocalDate, bodies: Boolean, notes: Boolean, days: Long): String = buildString {
            append("Today is ").append(today).append(".\n")
            val tasks = live.filter { isTask(it) && open(it) }
            fun byDue(r: EntityRecord) = f(r, "due").ifBlank { "9999" }

            val loose = tasks.filter { projectOf(it) == null }.sortedBy(::byDue)
            if (loose.isNotEmpty()) {
                append("\n## Inbox\n")
                loose.forEach { append(line(it, bodies)).append('\n') }
            }
            for (p in live.filter { it.type == "project" }.sortedBy { title(it).lowercase() }) {
                append("\n## Project: ").append(title(p)).append(" (id ").append(p.id).append(")\n")
                if (bodies && f(p, "body").isNotBlank()) append(f(p, "body").replace('\n', ' ').take(200)).append('\n')
                val inside = live.filter { it.type != "project" && projectOf(it)?.id == p.id }
                inside.filter { isTask(it) && open(it) }.sortedBy(::byDue).forEach { append(line(it, bodies)).append('\n') }
                if (notes) inside.filter { it.type == "note" }.forEach { append(line(it, bodies)).append('\n') }
            }
            val until = today.plusDays(days).toString()
            val events = live.filter { it.type == "event" && f(it, "dtstart").take(10).let { d -> d >= today.toString() && d <= until } }
                .sortedBy { f(it, "dtstart") }
            if (events.isNotEmpty()) {
                append("\n## Calendar\n")
                events.forEach { e ->
                    append("- ").append(f(e, "dtstart")).append(' ').append(f(e, "summary"))
                    if (f(e, "location").isNotBlank()) append(" @ ").append(f(e, "location"))
                    append(" (id ").append(e.id).append(")\n")
                }
            }
            if (notes) {
                val free = live.filter { it.type == "note" && projectOf(it) == null && f(it, "parent").isBlank() }
                if (free.isNotEmpty()) {
                    append("\n## Notes\n")
                    free.forEach { append(line(it, bodies)).append('\n') }
                }
            }
        }
    }
}
