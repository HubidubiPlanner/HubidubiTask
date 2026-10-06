package com.hubidubitask.mobile.browse

import com.hubidubitask.core.EntityRecord

/**
 * What one item looks like when you open it: its date, and whatever the agent
 * has already said about it.
 *
 * A review is written once and kept on the item, so opening the same note next
 * week shows the same words instead of asking a cold model to think again.
 * Pure, so those rules are testable without the sheet around them.
 */
object ItemDetail {

    /** The auto-generated capture names, which used to end in a timestamp:
     *  "Photo Sep 21 14:32". Anchored and narrow on purpose — a name the user
     *  typed that happens to mention a date is theirs, and stays. */
    private val STAMPED = Regex("""(Photo|Voice memo|Document) [A-Z][a-z]{2} \d{1,2} \d{1,2}:\d{2}""")

    /** What an item is called on screen.
     *
     *  Items captured before the rename still carry the minute they were taken
     *  in their stored name, so a list with no date fields in it can still be
     *  full of dates. The stored name is left alone; only the reading changes. */
    fun cleanName(raw: String): String =
        if (STAMPED.matches(raw.trim())) STAMPED.find(raw.trim())!!.groupValues[1] else raw

    /** The item's date, or null when it has none. */
    fun dueOf(record: EntityRecord): String? =
        record.fields["due"]?.takeIf { it.isNotBlank() }

    /** The stored review, or null. Cleared reviews are written as an empty
     *  string rather than removed — the field has to travel to say "gone" —
     *  so blank and absent mean the same thing here. */
    fun reviewOf(record: EntityRecord): String? =
        record.fields["review"]?.trim()?.takeIf { it.isNotEmpty() }

    fun hasReview(record: EntityRecord): Boolean = reviewOf(record) != null

    /** What kind of thing this is, in the user's words. */
    fun typeLabel(record: EntityRecord): String = when (record.type) {
        "note" -> when (record.fields["kind"]) {
            "image" -> "Photo"
            "audio" -> "Voice note"
            "document" -> "Document"
            else -> "Note"
        }
        "inbox_item" -> when (record.fields["kind"]) {
            "image" -> "Photo task"
            "audio" -> "Voice task"
            "document" -> "Document task"
            else -> "Task"
        }
        "next_action" -> "Next action"
        "project" -> "Project"
        "event" -> "Event"
        else -> record.type.replace('_', ' ')
    }

    /** When this was captured, if the item records it.
     *
     *  A photo used to be *named* for the minute it was taken, which put a date
     *  in every list that was supposed to be free of them. The time is still
     *  worth having — it just belongs here, where you opened the item to ask. */
    fun capturedAt(record: EntityRecord): String? {
        record.fields["created"]?.takeIf { it.isNotBlank() }?.let { return it.take(16).replace('T', ' ') }
        val date = record.fields["date"]?.takeIf { it.isNotBlank() } ?: return null
        val time = record.fields["time"]?.takeIf { it.isNotBlank() }
        return if (time == null) date else "$date $time"
    }

    /** The line under the title: what it is, and when it is due. */
    fun subtitle(record: EntityRecord): String {
        val due = dueOf(record)
        return if (due == null) typeLabel(record) else "${typeLabel(record)} · due $due"
    }
}
