package com.hubidubitask.desktop.agent

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.AgentAction
import com.hubidubitask.core.agent.AgentItems
import com.hubidubitask.core.feed.Feeds
import com.hubidubitask.core.nowUtc
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.desktop.Store
import com.hubidubitask.mobile.tree.ProjectTree

/**
 * This computer's items as a Harness question carries them, and where the
 * changes its item tools made land: written as if typed here, so they reach
 * the phone on its next sync. The Harness itself runs elsewhere (src/harness).
 */
class Items(private val store: Store, private val onChange: () -> Unit = {}) : AgentItems {

    override fun records(): List<EntityRecord> = store.all().filter { it.type != Feeds.TYPE }

    override fun apply(actions: List<AgentAction>) {
        for (a in actions) when (a.op) {
            "add_task" -> addTask(a.text ?: continue,
                a.project?.let(store::get)?.takeIf { it.type == "project" && !it.deleted }, a.due)
            "capture" -> capture(a.text ?: continue)
            "mark_done" -> markDone(a.id ?: continue)
        }
        if (actions.isNotEmpty()) onChange()
    }

    private fun title(r: EntityRecord) = r.fields["title"] ?: r.fields["text"] ?: r.id

    private fun newId(prefix: String) = prefix + "_" + PeerPairing.nonce().take(8)
        .map { "abcdefghijklmnopqrstuvwxyz234567"[it.code % 32] }.joinToString("")

    fun addTask(text: String, project: EntityRecord?, due: String?): EntityRecord {
        val rec = EntityRecord(newId("t"), "next_action", nowUtc(), buildMap {
            put("text", text.trim())
            put("project", project?.let { ProjectTree.slugOf(title(it)) } ?: "inbox")
            project?.let { put("parent", it.id) }
            due?.takeIf { it.isNotBlank() }?.let { put("due", it) }
        })
        store.put(rec); return rec
    }

    fun capture(text: String): EntityRecord {
        val now = java.time.LocalDateTime.now()
        val rec = EntityRecord(newId("i"), "inbox_item", nowUtc(), mapOf("text" to text.trim(),
            "date" to now.toLocalDate().toString(), "time" to "%02d:%02d".format(now.hour, now.minute)))
        store.put(rec); return rec
    }

    fun markDone(id: String): EntityRecord? {
        val rec = store.get(id)?.takeIf { !it.deleted && (it.type == "next_action" || it.type == "inbox_item") }
            ?: return null
        val done = if (rec.type == "inbox_item") rec.tombstone(nowUtc())
                   else rec.copy(updated = nowUtc(), fields = rec.fields + ("done" to "true"))
        store.put(done); return done
    }
}
