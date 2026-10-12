package com.hubidubitask.mobile.sync

import android.content.Context
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.mobile.data.Database
import com.hubidubitask.mobile.data.RoomLocalStore
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The phone's items: reads and writes on its own database. Every write is
 * logged for peer sync, which carries it to the paired computer (Mac or
 * Linux) on the next round; attachments sit in blobsDir by their hash.
 */
class Sync private constructor(
    val store: RoomLocalStore,
    private val blobsDir: File,
) {
    fun capture(text: String, due: String? = null): String {
        // The never-fail path: append to the outbox, no network, no validation.
        val id = "i_" + randomSuffix()
        val record = EntityRecord(
            id = id, type = "inbox_item", updated = com.hubidubitask.core.nowUtc(),
            fields = buildMap {
                put("text", text); put("time", time()); put("date", date())
                if (!due.isNullOrBlank()) put("due", due)
            },
        )
        store.put(record)
        return id
    }

    /** A photo or voice memo captured as a *task*, not a note.
     *
     *  The user answers "task or note?" at capture time, so the same media has
     *  to be able to land in the GTD inbox. Bytes travel the same blob channel
     *  as a note's; only the entity type differs. */
    fun captureMedia(kind: String, bytes: ByteArray, mime: String, text: String,
                     due: String? = null): String {
        val sha = sha256(bytes)
        blobsDir.mkdirs()
        val local = File(blobsDir, sha)
        if (!local.exists()) local.writeBytes(bytes)
        val id = "i_" + randomSuffix()
        val record = EntityRecord(
            id = id, type = "inbox_item", updated = com.hubidubitask.core.nowUtc(),
            fields = buildMap {
                put("text", text); put("time", time()); put("date", date())
                put("kind", kind); put("media_sha256", sha); put("media_mime", mime)
                put("media_bytes", bytes.size.toString())
                if (!due.isNullOrBlank()) put("due", due)
            },
        )
        store.put(record)
        return id
    }

    // ---- notes: text / image / audio (attachments travel by hash with peer sync) ----

    /** A plain text note. */
    fun noteText(title: String, body: String = "", due: String? = null,
                 parent: String? = null): String {
        val id = "n_" + randomSuffix()
        val f = hashMapOf<String, String?>("title" to title, "kind" to "text", "body" to body)
        if (!due.isNullOrBlank()) f["due"] = due
        // A note under a task: what the task needs you to know or check.
        if (!parent.isNullOrBlank()) f["parent"] = parent
        upsert("note", id, f, com.hubidubitask.core.nowUtc())
        return id
    }

    /** An image or audio note. The bytes are cached locally by content hash and
     *  queued for upload; the note entity only references them by SHA-256. */
    fun noteMedia(kind: String, bytes: ByteArray, mime: String, title: String,
                  due: String? = null): String {
        val sha = sha256(bytes)
        blobsDir.mkdirs()
        val local = File(blobsDir, sha)
        if (!local.exists()) local.writeBytes(bytes)
        val id = "n_" + randomSuffix()
        val f = hashMapOf<String, String?>(
            "title" to title, "kind" to kind, "media_sha256" to sha,
            "media_mime" to mime, "media_bytes" to bytes.size.toString())
        if (!due.isNullOrBlank()) f["due"] = due
        upsert("note", id, f, com.hubidubitask.core.nowUtc())
        return id
    }

    fun notes(): List<EntityRecord> = store.byType("note")

    /** Local bytes for a media note, or null until peer sync has brought them. */
    fun mediaFile(sha: String): File? = File(blobsDir, sha).takeIf { it.exists() }

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

    // ---- writes (logged for peer sync; they reach the computer on the next round) ----

    /** Client-side clarify commit: create the target entity, tombstone the
     *  inbox item. Mirrors the gtd-file skill; deletion beats edit downstream. */
    fun fileClarify(inboxId: String, kind: String, text: String,
                    context: String? = null, project: String? = null) {
        val now = com.hubidubitask.core.nowUtc()
        when (kind) {
            "trash" -> {}
            "next_action" -> {
                val f = HashMap<String, String?>()
                f["text"] = text   // no "done": absent means open (see addNextAction)
                f["project"] = project ?: "inbox"
                if (context != null) f["context"] = context
                upsert("next_action", "t_" + randomSuffix(), f, now)
            }
            "waiting_for" -> upsert("waiting_for", "t_" + randomSuffix(),
                hashMapOf("text" to text), now)
            "someday" -> upsert("someday", "t_" + randomSuffix(),
                hashMapOf("text" to text), now)
            "project" -> upsert("project", "p_" + randomSuffix(),
                hashMapOf("title" to text, "status" to "active"), now)
        }
        // Remove the inbox item.
        val item = store.get(inboxId)
        if (item != null) {
            store.put(item.tombstone())
        }
    }

    fun markDone(id: String) {
        val rec = store.get(id) ?: return
        val updated = rec.copy(updated = com.hubidubitask.core.nowUtc(),
            fields = rec.fields + ("done" to "true"))
        store.put(updated)
    }

    /** Put a date on an item, or clear it with null.
     *
     *  Dated items are what the Next view lists, so this is how something moves
     *  from "someday" into the day it is actually due. */
    fun setDue(id: String, due: String?): Boolean {
        val rec = store.get(id) ?: return false
        val fields = rec.fields.toMutableMap()
        if (due.isNullOrBlank()) fields.remove("due") else fields["due"] = due
        val updated = rec.copy(updated = com.hubidubitask.core.nowUtc(), fields = fields)
        store.put(updated)
        return true
    }

    /** Keep what the agent said about an item.
     *
     *  Stored on the item so it is there the next time the item is opened,
     *  rather than asked for — and paid for — again. Empty clears it. */
    /** Keep a review on the item, with the fingerprint of what it read ([of]). */
    fun setReview(id: String, text: String?, of: String? = null): Boolean {
        val rec = store.get(id) ?: return false
        val fields = rec.fields.toMutableMap()
        if (text.isNullOrBlank()) fields["review"] = "" else fields["review"] = text.trim()
        fields["review_of"] = if (text.isNullOrBlank()) "" else of.orEmpty()
        val updated = rec.copy(updated = com.hubidubitask.core.nowUtc(), fields = fields)
        store.put(updated)
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
        upsert("project", id, fields, com.hubidubitask.core.nowUtc())
        return id
    }

    /** Rename a project. Its items point at it by id, so they stay inside it. */
    fun renameProject(id: String, title: String): Boolean {
        val rec = store.get(id)?.takeIf { it.type == "project" } ?: return false
        val updated = rec.copy(updated = com.hubidubitask.core.nowUtc(),
            fields = rec.fields + ("title" to title.trim()))
        store.put(updated)
        return true
    }

    /** Rewrite a note's words in place. Only a note: a task's text is its line
     *  in the workstation's list, and the menu is where tasks are changed. */
    fun editNote(id: String, title: String, body: String): Boolean {
        val rec = store.get(id)?.takeIf { it.type == "note" } ?: return false
        val updated = rec.copy(updated = com.hubidubitask.core.nowUtc(),
            fields = rec.fields + ("title" to title.trim()) + ("body" to body.trim()))
        store.put(updated)
        return true
    }

    /** Re-nest an item under [parentId] (null = top level).
     *
     *  Only the parent pointer changes; the item keeps its text, state and any
     *  attachment, so moving something around the tree can never lose content. */
    fun moveTo(id: String, parentId: String?): Boolean {
        val rec = store.get(id) ?: return false
        val fields = rec.fields.toMutableMap()
        if (parentId.isNullOrBlank()) fields.remove("parent") else fields["parent"] = parentId
        val updated = rec.copy(updated = com.hubidubitask.core.nowUtc(), fields = fields)
        store.put(updated)
        return true
    }

    /** A next action filed under a project (or loose when [parentId] is null). */
    fun addNextAction(text: String, parentId: String?, projectSlug: String?, due: String?,
                      body: String? = null, id: String = "t_" + randomSuffix()): String {
        val f = HashMap<String, String?>()
        // No "done": absent means open. An explicit "false" is a truthy string
        // to a workstation that predates the fix for it.
        f["text"] = text
        // Both: `parent` is what the Projects view nests by; the slug decides
        // which current/<slug>.md file the workstation writes it into.
        f["project"] = projectSlug ?: "inbox"
        if (parentId != null) f["parent"] = parentId
        if (due != null) f["due"] = due
        // Longer than a line: the workstation keeps it in details/<id>.md.
        if (body != null) f["body"] = body
        upsert("next_action", id, f, com.hubidubitask.core.nowUtc())
        return id
    }

    fun events(): List<EntityRecord> = store.byType("event")

    // ---- feeds: watched pages, each turned into at most one task per period ----

    fun get(id: String): EntityRecord? = store.get(id)

    /** Any record with [id], deleted ones included: a feed's task for a period is made once. */
    fun exists(id: String): Boolean = store.get(id) != null

    fun feeds(): List<EntityRecord> = store.byType(com.hubidubitask.core.feed.Feeds.TYPE)

    fun newFeed(fields: Map<String, String?>): EntityRecord {
        val rec = EntityRecord(id = "f_" + randomSuffix(), type = com.hubidubitask.core.feed.Feeds.TYPE,
            updated = com.hubidubitask.core.nowUtc(), fields = fields.filterValues { it != null })
        putFeed(rec)
        return rec
    }

    /** A feed as it now stands; peer sync carries it to the computer. */
    fun putFeed(rec: EntityRecord) {
        store.put(rec)
    }

    /**
     * A calendar event. Its id is derived from the UID exactly as the
     * workstation's indexer derives it, so the phone's copy and the one read
     * back from the .ics file are the same record, and importing the same
     * event twice is a no-op.
     */
    fun addEvent(o: com.hubidubitask.mobile.importer.Ics.Occurrence, calendar: String): Boolean {
        val id = eventId(o.uid)
        if (store.get(id) != null) return false
        val f = HashMap<String, String?>()
        f["uid"] = o.uid; f["summary"] = o.summary; f["dtstart"] = o.dtstart
        f["calendar"] = calendar; f["all_day"] = o.allDay.toString()
        o.dtend?.let { f["dtend"] = it }
        o.location?.let { f["location"] = it }
        upsert("event", id, f, com.hubidubitask.core.nowUtc())
        return true
    }

    private fun upsert(type: String, id: String, fields: Map<String, String?>, now: String) {
        val rec = EntityRecord(id = id, type = type, updated = now, fields = fields)
        store.put(rec)
    }

    companion object {
        fun open(context: Context): Sync =
            Sync(RoomLocalStore(Database.get(context)), File(context.filesDir, "blobs"))

        /** One round with the paired computer, if there is one. */
        fun syncMac(context: Context): com.hubidubitask.mobile.net.MacLink.Round? {
            val db = Database.get(context)
            val peers = com.hubidubitask.mobile.data.PhonePeerStore(db, RoomLocalStore(db))
            return com.hubidubitask.mobile.net.MacLink.sync(context, peers, File(context.filesDir, "blobs"))
        }
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

/** "e_" + the first 16 hex of sha256(uid): the workstation's formats/ics.py rule. */
fun eventId(uid: String): String {
    val d = java.security.MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
    return "e_" + d.joinToString("") { "%02x".format(it) }.take(16)
}
