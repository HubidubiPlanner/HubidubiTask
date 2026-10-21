package com.hubidubitask.core

import java.security.MessageDigest
import java.security.SecureRandom

/** Where new items go: the phone's database or the computer's store. */
interface ItemSink {
    fun get(id: String): EntityRecord?
    fun put(record: EntityRecord)
    fun byType(type: String): List<EntityRecord>
}

/** Ids as both apps make them. */
object Ids {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val random = SecureRandom()

    /** [prefix], an underscore and eight random letters or digits, e.g. "t_k3mq7xza". */
    fun new(prefix: String): String = prefix + "_" + suffix()

    fun suffix(): String =
        ByteArray(8).also(random::nextBytes).joinToString("") { ALPHABET[it.toInt() and 31].toString() }

    /** "e_" and the first 16 hex of sha256([uid]): the same event has the same id everywhere. */
    fun event(uid: String): String = "e_" + MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * New items, with the fields both apps write. [origin] says who wrote the
 * words when it was not the user: "feed" for a feed's task, "import" for what
 * came from another app, "share" for text shared in. An agent reading them is
 * told so (see ItemsContext).
 */
class ItemWriter(private val sink: ItemSink, private val now: () -> String = ::nowUtc) {

    /** A project, nested under [parent] when given. Returns its id. */
    fun project(title: String, parent: String? = null, origin: String? = null): String {
        val id = Ids.new("p")
        put("project", id, mapOf("title" to title.trim(), "status" to "active",
            "parent" to parent?.ifBlank { null }, "origin" to origin))
        return id
    }

    /**
     * A next action in [parentId]'s project, or loose when it is null. Both
     * the parent and the project's slug are kept: the Projects view nests by
     * `parent`, older computers file by `project`. No "done": absent means open.
     */
    fun nextAction(text: String, parentId: String?, projectSlug: String?, due: String?,
                   body: String? = null, id: String = Ids.new("t"), origin: String? = null): String {
        put("next_action", id, mapOf("text" to text, "project" to (projectSlug ?: "inbox"),
            "parent" to parentId, "due" to due, "body" to body, "origin" to origin))
        return id
    }

    /** A text note, under the task [parent] when given. */
    fun note(title: String, body: String = "", due: String? = null, parent: String? = null,
             origin: String? = null): String {
        val id = Ids.new("n")
        put("note", id, mapOf("title" to title, "kind" to "text", "body" to body,
            "due" to due?.ifBlank { null }, "parent" to parent?.ifBlank { null }, "origin" to origin))
        return id
    }

    /** A calendar event, once: false when one with the same uid is already here. */
    fun event(uid: String, summary: String, dtstart: String, dtend: String?, allDay: Boolean,
              location: String?, calendar: String): Boolean {
        val id = Ids.event(uid)
        if (sink.get(id) != null) return false
        put("event", id, mapOf("uid" to uid, "summary" to summary, "dtstart" to dtstart,
            "dtend" to dtend, "calendar" to calendar, "all_day" to allDay.toString(), "location" to location))
        return true
    }

    private fun put(type: String, id: String, fields: Map<String, String?>) =
        sink.put(EntityRecord(id, type, now(), fields.filterValues { it != null }))
}
