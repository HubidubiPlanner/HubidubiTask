package com.hubidubios.mobile.browse

import com.hubidubios.core.EntityRecord

/**
 * What one item looks like when you open it: its date, and whatever the agent
 * has already said about it.
 *
 * A review is written once and kept on the item, so opening the same note next
 * week shows the same words instead of asking a cold model to think again.
 * Pure, so those rules are testable without the sheet around them.
 */
object ItemDetail {

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
        else -> record.type.replace('_', ' ')
    }

    /** The line under the title: what it is, and when it is due. */
    fun subtitle(record: EntityRecord): String {
        val due = dueOf(record)
        return if (due == null) typeLabel(record) else "${typeLabel(record)} · due $due"
    }
}
