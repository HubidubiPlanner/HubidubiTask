package com.hubidubitask.desktop.harness

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.nowUtc
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.desktop.Store
import com.hubidubitask.mobile.tree.ProjectTree
import java.time.LocalDate

/**
 * The user's items as the harness sees them: what the MCP tools read and
 * change, and what the Markdown view is written from. One place, so both
 * describe the same data the same way.
 */
class Items(private val store: Store, private val onChange: () -> Unit = {}) {

    fun all(): List<EntityRecord> = store.all()
    fun projects() = all().filter { it.type == "project" }.sortedBy { title(it).lowercase() }

    fun isTask(r: EntityRecord) = r.type == "next_action" || r.type == "inbox_item"
    fun open(r: EntityRecord) = r.fields["done"] != "true"
    fun title(r: EntityRecord) = r.fields["title"] ?: r.fields["text"] ?: r.fields["summary"] ?: r.id

    fun projectOf(r: EntityRecord, byId: Map<String, EntityRecord>): EntityRecord? {
        var p = r.fields["parent"]?.let(byId::get)
        repeat(8) { if (p == null || p!!.type == "project") return p; p = p!!.fields["parent"]?.let(byId::get) }
        return p?.takeIf { it.type == "project" }
    }

    /** One line about one item, the shape every tool answers in. */
    fun line(r: EntityRecord, byId: Map<String, EntityRecord> = all().associateBy { it.id }): String {
        val f = r.fields
        val kind = when (r.type) {
            "next_action" -> if (open(r)) "task" else "done task"
            "inbox_item" -> "inbox"
            "note" -> "note"
            "event" -> "event"
            "project" -> "project"
            "feed" -> "feed"
            else -> r.type
        }
        val parts = mutableListOf("[$kind] ${title(r)}")
        f["due"]?.takeIf { it.isNotBlank() }?.let { parts += "due $it" }
        f["dtstart"]?.let { parts += "at $it" }
        projectOf(r, byId)?.takeIf { r.type != "project" }?.let { parts += "in ${title(it)}" }
        f["body"]?.takeIf { it.isNotBlank() }?.let { parts += "— " + it.replace('\n', ' ').take(200) }
        parts += "(id ${r.id})"
        return parts.joinToString(" · ")
    }

    fun search(query: String, limit: Int): List<EntityRecord> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return all().filter { r -> r.type != "feed" && words.all { w ->
            r.fields.values.any { it?.lowercase()?.contains(w) == true } } }
            .sortedWith(compareBy({ !(isTask(it) && open(it)) }, { it.fields["due"] ?: "9999" }))
            .take(limit)
    }

    /** Open tasks due within [days] (overdue included) and events in that time, soonest first. */
    fun upcoming(days: Int, today: LocalDate = LocalDate.now()): List<EntityRecord> {
        val until = today.plusDays(days.toLong()).toString()
        return all().filter { r ->
            (isTask(r) && open(r) && (r.fields["due"]?.takeIf { it.isNotBlank() }?.let { it <= until } == true)) ||
                (r.type == "event" && r.fields["dtstart"]?.let { it >= today.toString() && it.take(10) <= until } == true)
        }.sortedBy { it.fields["due"] ?: it.fields["dtstart"] }
    }

    /** Tasks and notes in [project], open ones first. */
    fun inProject(project: EntityRecord): List<EntityRecord> {
        val byId = all().associateBy { it.id }
        return all().filter { it.type != "project" && projectOf(it, byId)?.id == project.id }
            .sortedWith(compareBy({ it.type == "note" }, { !open(it) }, { it.fields["due"] ?: "9999" }))
    }

    fun findProject(name: String): EntityRecord? {
        val n = name.trim().lowercase()
        return projects().firstOrNull { title(it).lowercase() == n || it.id == name.trim() }
            ?: projects().firstOrNull { title(it).lowercase().contains(n) }
    }

    /** Items in no project: captures and loose tasks. */
    fun loose(): List<EntityRecord> {
        val byId = all().associateBy { it.id }
        return all().filter { isTask(it) && open(it) && projectOf(it, byId) == null }
    }

    // ---- changes

    private fun newId(prefix: String) = prefix + "_" + PeerPairing.nonce().take(8)
        .map { "abcdefghijklmnopqrstuvwxyz234567"[it.code % 32] }.joinToString("")

    fun addTask(text: String, project: EntityRecord?, due: String?): EntityRecord {
        val rec = EntityRecord(newId("t"), "next_action", nowUtc(), buildMap {
            put("text", text.trim())
            put("project", project?.let { ProjectTree.slugOf(title(it)) } ?: "inbox")
            project?.let { put("parent", it.id) }
            due?.takeIf { it.isNotBlank() }?.let { put("due", it) }
        })
        store.put(rec); onChange(); return rec
    }

    fun capture(text: String): EntityRecord {
        val now = java.time.LocalDateTime.now()
        val rec = EntityRecord(newId("i"), "inbox_item", nowUtc(), mapOf("text" to text.trim(),
            "date" to now.toLocalDate().toString(), "time" to "%02d:%02d".format(now.hour, now.minute)))
        store.put(rec); onChange(); return rec
    }

    fun markDone(id: String): EntityRecord? {
        val rec = store.get(id)?.takeIf { !it.deleted && isTask(it) } ?: return null
        val done = if (rec.type == "inbox_item") rec.tombstone(nowUtc())
                   else rec.copy(updated = nowUtc(), fields = rec.fields + ("done" to "true"))
        store.put(done); onChange(); return done
    }
}
