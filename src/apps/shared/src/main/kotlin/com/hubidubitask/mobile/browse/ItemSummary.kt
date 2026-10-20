package com.hubidubitask.mobile.browse

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.ItemsContext
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.mobile.tree.ProjectTree
import java.security.MessageDigest

/**
 * A review is a summary of what happened: in a project, from its tasks and
 * notes; in a task, from its text and what is filed under it; in a note, from
 * its words. It is kept on the item with a fingerprint of everything it read,
 * so the item can say when it no longer describes what is there: a task done,
 * a note added or edited, anything moved in or out.
 *
 * Pure, so what a review reads and when it goes out of date are testable
 * without a model or a screen.
 */
object ItemSummary {

    /** The summary itself, and the fingerprint of what it was made from. */
    const val TEXT = "review"
    const val OF = "review_of"

    /** What the screens show under an item's title. */
    data class Status(val text: String, val current: Boolean)

    private val OWN = setOf(TEXT, OF)
    private val ITEMS = setOf("next_action", "inbox_item", "note")

    /** Everything filed under [root], all the way down, done tasks included. */
    fun under(root: EntityRecord, all: List<EntityRecord>): List<EntityRecord> = Index(all).under(root.id)

    /** What [root] and [under] say now; a stored review read something else when this differs. */
    fun fingerprint(root: EntityRecord, under: List<EntityRecord>): String {
        // Blank and absent mean the same: one device may keep an empty field
        // the other never wrote, and that is no change.
        fun line(r: EntityRecord) = r.id + "\u0000" + r.type + "\u0000" + r.fields
            .filter { (k, v) -> k !in OWN && !v.isNullOrBlank() }.toSortedMap().entries
            .joinToString("\u0000") { "${it.key}=${it.value}" }
        val text = (listOf(line(root)) + under.map(::line).sorted()).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Every reviewed item's summary, and whether it still describes what is there. */
    fun statuses(all: List<EntityRecord>): Map<String, Status> {
        val index = Index(all)
        return all.filter { !it.deleted }.mapNotNull { r ->
            val text = ItemDetail.reviewOf(r) ?: return@mapNotNull null
            r.id to Status(text, r.fields[OF] == fingerprint(r, index.under(r.id)))
        }.toMap()
    }

    /** The question put to the agent: what happened, from what is filed here. */
    fun prompt(root: EntityRecord, under: List<EntityRecord>): String {
        val ask = when {
            root.type == "project" -> "Summarise what has happened in this project, from its " +
                "tasks and notes below: what is done, what was decided or learned, and what is " +
                "still open."
            root.type == "note" -> "Summarise this note" +
                (if (under.isEmpty()) "" else " and what is filed under it") +
                ": what it records, and anything in it that still needs doing."
            else -> "Summarise what has happened with this task, from its text" +
                (if (under.isEmpty()) "" else " and the notes and tasks under it") +
                ": what is done, what was decided or learned, and what is still open."
        }
        val shape = " One short paragraph of plain sentences, no headings or lists."
        // The answer is read in the app, so it comes in the app's language.
        val inLanguage = I18n.language.takeIf { it != Language.EN }
            ?.let { " Answer in ${it.locale.getDisplayLanguage(java.util.Locale.ENGLISH)}." }.orEmpty()
        val depth = depths(root, under)
        val lines = mutableListOf(describe(root, 0))
        if (under.isNotEmpty()) lines += "\nFiled under it:"
        var size = lines.sumOf { it.length }
        for ((i, r) in under.withIndex()) {
            val l = describe(r, depth[r.id] ?: 1)
            if (size + l.length > LIMIT) { lines += "- … ${under.size - i} more not shown"; break }
            lines += l; size += l.length
        }
        // The items' words are data, fenced: a feed's task or a shared text was
        // written by someone else, and the user's own agent can run commands.
        return "$ask$shape$inLanguage\n\n" +
            ItemsContext.fence(lines.joinToString("\n"), "The item and what is filed under it")
    }

    private const val LIMIT = 16_000
    private const val BODY = 1_500

    /** One item for the prompt: what it is, its name, state, date and words. */
    private fun describe(r: EntityRecord, depth: Int): String {
        val kind = when {
            r.type == "project" -> "project"
            r.type == "note" -> when (r.fields["kind"]) {
                "image" -> "photo"; "audio" -> "voice memo"; "document" -> "file"; else -> "note"
            }
            r.fields["done"] == "true" -> "task, done"
            else -> "task"
        }
        val due = r.fields["due"]?.takeIf { it.isNotBlank() }?.let { " · due $it" }.orEmpty()
        val head = "[$kind] ${ReviewBrowse.labelOf(r)}$due" + ItemsContext.origin(r)?.let { " $it" }.orEmpty()
        val body = r.fields["body"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > BODY) it.take(BODY) + "…" else it }
        if (depth == 0) return if (body == null) head else "$head\n$body"
        val pad = "  ".repeat(depth - 1)
        return "$pad- $head" + (body?.lines()?.joinToString("") { "\n$pad  $it" }).orEmpty()
    }

    private fun depths(root: EntityRecord, under: List<EntityRecord>): Map<String, Int> {
        val parent = under.associate { it.id to it.fields["parent"] }
        return under.associate { r ->
            var d = 1; var p = parent[r.id]
            while (p != null && p != root.id && d < 20) { d++; p = parent[p] }
            r.id to d
        }
    }

    /** Who is filed under whom, built once for every item asked about. */
    private class Index(all: List<EntityRecord>) {
        private val byParent: Map<String?, List<EntityRecord>>
        init {
            val idBySlug = all.filter { it.type == "project" && !it.deleted }
                .associate { ProjectTree.slugOf(it.fields["title"] ?: it.id) to it.id }
            byParent = all.filter { !it.deleted && it.type in ITEMS }
                .groupBy { ProjectTree.parentOf(it, idBySlug) }
        }

        fun under(id: String): List<EntityRecord> {
            val out = mutableListOf<EntityRecord>()
            val seen = hashSetOf(id)
            fun walk(at: String) {
                byParent[at].orEmpty().sortedWith(compareBy({ it.type == "note" }, { ReviewBrowse.labelOf(it).lowercase() }))
                    .forEach { if (seen.add(it.id)) { out += it; walk(it.id) } }
            }
            walk(id)
            return out
        }
    }
}
