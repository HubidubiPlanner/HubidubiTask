package com.hubidubios.mobile.sync

import android.content.Context
import com.hubidubios.core.EntityRecord
import com.hubidubios.core.Outbox
import com.hubidubios.core.SyncEngine
import com.hubidubios.mobile.data.BlobUploadDao
import com.hubidubios.mobile.data.BlobUploadRow
import com.hubidubios.mobile.data.Database
import com.hubidubios.mobile.data.RoomLocalStore
import com.hubidubios.mobile.data.RoomOutboxStore
import com.hubidubios.mobile.net.PairedServer
import com.hubidubios.mobile.net.PinnedTransport
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Wires the Room-backed stores to the pure-Kotlin core SyncEngine. Everything
 * here except the store/transport construction is tested in :core.
 */
class Sync private constructor(
    val store: RoomLocalStore,
    val outbox: Outbox,
    private val engine: SyncEngine?,
    private val transport: PinnedTransport?,
    private val blobDao: BlobUploadDao,
    private val blobsDir: File,
) {
    fun capture(text: String): String {
        // The never-fail path: append to the outbox, no network, no validation.
        val id = "i_" + randomSuffix()
        val record = EntityRecord(
            id = id, type = "inbox_item", updated = com.hubidubios.core.nowUtc(),
            fields = mapOf("text" to text, "time" to time(), "date" to date()),
        )
        store.put(record)
        outbox.enqueueUpsert(record)
        return id
    }

    /** A photo or voice memo captured as a *task*, not a note.
     *
     *  The user answers "task or note?" at capture time, so the same media has
     *  to be able to land in the GTD inbox. Bytes travel the same blob channel
     *  as a note's; only the entity type differs. */
    fun captureMedia(kind: String, bytes: ByteArray, mime: String, text: String): String {
        val sha = sha256(bytes)
        blobsDir.mkdirs()
        val local = File(blobsDir, sha)
        if (!local.exists()) local.writeBytes(bytes)
        val id = "i_" + randomSuffix()
        val record = EntityRecord(
            id = id, type = "inbox_item", updated = com.hubidubios.core.nowUtc(),
            fields = mapOf(
                "text" to text, "time" to time(), "date" to date(),
                "kind" to kind, "media_sha256" to sha, "media_mime" to mime,
                "media_bytes" to bytes.size.toString(),
            ),
        )
        store.put(record)
        outbox.enqueueUpsert(record)
        blobDao.add(BlobUploadRow(sha256 = sha, local_path = local.path,
            mime = mime, note_id = id))
        uploadPendingBlobs()   // best-effort now; the queue survives if offline
        return id
    }

    // ---- notes: text / image / audio (each uploaded to the workstation) ----

    /** A plain text note. */
    fun noteText(title: String, body: String = ""): String {
        val id = "n_" + randomSuffix()
        upsert("note", id, hashMapOf("title" to title, "kind" to "text",
            "body" to body), com.hubidubios.core.nowUtc())
        return id
    }

    /** An image or audio note. The bytes are cached locally by content hash and
     *  queued for upload; the note entity only references them by SHA-256. */
    fun noteMedia(kind: String, bytes: ByteArray, mime: String, title: String): String {
        val sha = sha256(bytes)
        blobsDir.mkdirs()
        val local = File(blobsDir, sha)
        if (!local.exists()) local.writeBytes(bytes)
        val id = "n_" + randomSuffix()
        upsert("note", id, hashMapOf(
            "title" to title, "kind" to kind, "media_sha256" to sha,
            "media_mime" to mime, "media_bytes" to bytes.size.toString(),
        ), com.hubidubios.core.nowUtc())
        blobDao.add(BlobUploadRow(sha256 = sha, local_path = local.path,
            mime = mime, note_id = id))
        uploadPendingBlobs()   // best-effort now; the queue survives if offline
        return id
    }

    fun notes(): List<EntityRecord> = store.byType("note")

    /** Local bytes for a media note: the cached file, else downloaded on demand
     *  from the workstation and cached. Null if unavailable (offline, no blob). */
    fun mediaFile(sha: String): File? {
        val local = File(blobsDir, sha)
        if (local.exists()) return local
        val bytes = transport?.downloadBlob(sha) ?: return null
        blobsDir.mkdirs()
        local.writeBytes(bytes)
        return local
    }

    private fun uploadPendingBlobs() {
        val t = transport ?: return
        for (row in blobDao.pending()) {
            val f = File(row.local_path)
            if (!f.exists()) { blobDao.remove(row.sha256); continue }
            runCatching { if (t.uploadBlob(row.sha256, f.readBytes())) blobDao.remove(row.sha256) }
        }
    }

    /** Returns true if a sync cycle ran (a server is paired). */
    fun syncNow(): Boolean {
        val e = engine ?: return false
        e.sync()
        uploadPendingBlobs()
        return true
    }

    val isPaired: Boolean get() = engine != null

    // ---- reads for the native UI (from the local mirror; work offline) ----

    fun byType(type: String): List<EntityRecord> = store.byType(type)
    fun inbox(): List<EntityRecord> = store.byType("inbox_item")
    fun nextActions(): List<EntityRecord> =
        store.byType("next_action").filter { it.fields["done"] != "true" }
    fun projects(): List<EntityRecord> = store.byType("project")

    /** Everything with a date, soonest first. Notes count: a reference you need
     *  in hand on a given day is as time-bound as a task. */
    fun dated(): List<EntityRecord> =
        (store.byType("next_action") + store.byType("inbox_item") + store.byType("note"))
            .filter { !it.fields["due"].isNullOrBlank() && it.fields["done"] != "true" }
            .sortedBy { it.fields["due"] }

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
        val now = com.hubidubios.core.nowUtc()
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
        val updated = rec.copy(updated = com.hubidubios.core.nowUtc(),
            fields = rec.fields + ("done" to "true"))
        store.put(updated)
        outbox.enqueueUpsert(updated)
    }

    /** Put a date on an item, or clear it with null.
     *
     *  Dated items are what the Next view lists, so this is how something moves
     *  from "someday" into the day it is actually due. */
    fun setDue(id: String, due: String?): Boolean {
        val rec = store.get(id) ?: return false
        val fields = rec.fields.toMutableMap()
        if (due.isNullOrBlank()) fields.remove("due") else fields["due"] = due
        val updated = rec.copy(updated = com.hubidubios.core.nowUtc(), fields = fields)
        store.put(updated)
        outbox.enqueueUpsert(updated)
        return true
    }

    /** Delete an item and the attachment it owns.
     *
     *  Blobs are content-addressed, so the cached file is only removed when no
     *  other item still points at the same bytes — otherwise deleting one of
     *  two notes sharing a photo would blank the other. */
    fun delete(id: String): Boolean {
        val rec = store.get(id) ?: return false
        store.put(rec.tombstone())
        outbox.enqueueDelete(rec)
        rec.fields["media_sha256"]?.takeIf { it.isNotBlank() }?.let { sha ->
            val stillReferenced = (store.byType("note") + store.byType("inbox_item"))
                .any { it.id != id && it.fields["media_sha256"] == sha }
            if (!stillReferenced) File(blobsDir, sha).delete()
        }
        return true
    }

    /** Create a project, optionally nested under [parentId].
     *
     *  Returns its id so the caller can reveal it in the tree. */
    fun createProject(title: String, parentId: String? = null): String {
        val id = "p_" + randomSuffix()
        val fields = HashMap<String, String?>()
        fields["title"] = title.trim()
        fields["status"] = "active"
        if (!parentId.isNullOrBlank()) fields["parent"] = parentId
        upsert("project", id, fields, com.hubidubios.core.nowUtc())
        return id
    }

    /** Re-nest an item under [parentId] (null = top level).
     *
     *  Only the parent pointer changes; the item keeps its text, state and any
     *  attachment, so moving something around the tree can never lose content. */
    fun moveTo(id: String, parentId: String?): Boolean {
        val rec = store.get(id) ?: return false
        val fields = rec.fields.toMutableMap()
        if (parentId.isNullOrBlank()) fields.remove("parent") else fields["parent"] = parentId
        val updated = rec.copy(updated = com.hubidubios.core.nowUtc(), fields = fields)
        store.put(updated)
        outbox.enqueueUpsert(updated)
        return true
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
            val transport = server?.let { PinnedTransport(it) }
            val engine = transport?.let {
                SyncEngine(store, outbox, it, deviceIdFor(context))
            }
            val blobsDir = File(context.filesDir, "blobs")
            return Sync(store, outbox, engine, transport, db.blobUploads(), blobsDir)
        }

        private fun deviceIdFor(context: Context): String =
            com.hubidubios.mobile.security.Keystore.getSecret(context, "device-id") ?: "phone"
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
private fun randomSuffix(): String {
    val r = ByteArray(8).also { SecureRandom().nextBytes(it) }
    return r.joinToString("") { ALPHABET[it.toInt() and 31].toString() }
}
private fun time(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
private fun date(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
