package com.prodos.mobile.sync

import android.content.Context
import com.prodos.core.EntityRecord
import com.prodos.core.Outbox
import com.prodos.core.SyncEngine
import com.prodos.mobile.data.Database
import com.prodos.mobile.data.RoomLocalStore
import com.prodos.mobile.data.RoomOutboxStore
import com.prodos.mobile.net.PairedServer
import com.prodos.mobile.net.PinnedTransport
import java.security.SecureRandom

/**
 * Wires the Room-backed stores to the pure-Kotlin core SyncEngine. Everything
 * here except the store/transport construction is tested in :core.
 */
class Sync private constructor(
    val store: RoomLocalStore,
    val outbox: Outbox,
    private val engine: SyncEngine?,
) {
    fun capture(text: String): String {
        // The never-fail path: append to the outbox, no network, no validation.
        val id = "i_" + randomSuffix()
        val record = EntityRecord(
            id = id, type = "inbox_item", updated = com.prodos.core.nowUtc(),
            fields = mapOf("text" to text, "time" to time(), "date" to date()),
        )
        store.put(record)
        outbox.enqueueUpsert(record)
        return id
    }

    /** Returns true if a sync cycle ran (a server is paired). */
    fun syncNow(): Boolean {
        val e = engine ?: return false
        e.sync()
        return true
    }

    val isPaired: Boolean get() = engine != null

    // ---- reads for the native UI (from the local mirror; work offline) ----

    fun byType(type: String): List<EntityRecord> = store.byType(type)
    fun inbox(): List<EntityRecord> = store.byType("inbox_item")
    fun nextActions(): List<EntityRecord> =
        store.byType("next_action").filter { it.fields["done"] != "true" }
    fun projects(): List<EntityRecord> = store.byType("project")

    /** Projects with no open next action — the defining GTD failure. */
    fun projectsWithoutNextAction(): List<EntityRecord> {
        val have = nextActions().mapNotNull { it.fields["project"] }.toSet()
        return projects().filter { p ->
            val key = (p.fields["title"] ?: "").lowercase().replace(Regex("\\s+"), "-")
            key !in have
        }
    }

    // ---- writes (queued to the durable outbox; sync on reconnect) ----

    /** Client-side clarify commit: create the target entity, tombstone the
     *  inbox item. Mirrors the gtd-file skill; deletion beats edit downstream. */
    fun fileClarify(inboxId: String, kind: String, text: String,
                    context: String? = null, project: String? = null) {
        val now = com.prodos.core.nowUtc()
        when (kind) {
            "trash" -> {}
            "next_action" -> {
                val f = HashMap<String, String?>()
                f["text"] = text; f["done"] = "false"
                f["project"] = project ?: "inbox"
                if (context != null) f["context"] = context
                upsert("next_action", "t_" + randomSuffix(), f, now)
            }
            "waiting_for" -> upsert("waiting_for", "t_" + randomSuffix(),
                hashMapOf("text" to text, "done" to "false"), now)
            "someday" -> upsert("someday", "t_" + randomSuffix(),
                hashMapOf("text" to text, "done" to "false"), now)
            "project" -> upsert("project", "p_" + randomSuffix(),
                hashMapOf("title" to text, "status" to "active"), now)
        }
        // Remove the inbox item.
        val item = store.get(inboxId)
        if (item != null) {
            store.put(item.tombstone())
            outbox.enqueueDelete(item)
        }
    }

    fun markDone(id: String) {
        val rec = store.get(id) ?: return
        val updated = rec.copy(updated = com.prodos.core.nowUtc(),
            fields = rec.fields + ("done" to "true"))
        store.put(updated)
        outbox.enqueueUpsert(updated)
    }

    private fun upsert(type: String, id: String, fields: Map<String, String?>, now: String) {
        val rec = EntityRecord(id = id, type = type, updated = now, fields = fields)
        store.put(rec)
        outbox.enqueueUpsert(rec)
    }

    companion object {
        fun open(context: Context): Sync {
            val db = Database.get(context)
            val store = RoomLocalStore(db)
            val outbox = Outbox(RoomOutboxStore(db))
            val server = PairedServer.load(context)
            val engine = server?.let {
                SyncEngine(store, outbox, PinnedTransport(it), deviceIdFor(context))
            }
            return Sync(store, outbox, engine)
        }

        private fun deviceIdFor(context: Context): String =
            com.prodos.mobile.security.Keystore.getSecret(context, "device-id") ?: "phone"
    }
}

private val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
private fun randomSuffix(): String {
    val r = ByteArray(8).also { SecureRandom().nextBytes(it) }
    return r.joinToString("") { ALPHABET[it.toInt() and 31].toString() }
}
private fun time(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
private fun date(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
