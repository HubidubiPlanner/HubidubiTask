package com.hubidubios.mobile.browse

import com.hubidubios.core.EntityRecord

/**
 * Finding something during a review: pick items or notes, search, and — before
 * you have typed anything — see what you touched most recently, which is nearly
 * always what you are looking for.
 *
 * Pure so the ranking and matching rules can be tested without the UI.
 */
object ReviewBrowse {

    enum class Kind { ITEMS, NOTES }

    const val RECENT_LIMIT = 5

    /** The searchable text of a record, across the differently-named fields. */
    fun labelOf(record: EntityRecord): String =
        record.fields["title"] ?: record.fields["text"] ?: record.id

    /** Which records a [kind] covers. Items are the actionable things — inbox
     *  captures and next actions; notes are reference material. */
    fun pool(
        kind: Kind,
        inbox: List<EntityRecord>,
        nextActions: List<EntityRecord>,
        notes: List<EntityRecord>,
    ): List<EntityRecord> = when (kind) {
        Kind.ITEMS -> inbox + nextActions
        Kind.NOTES -> notes
    }

    /** Most recently modified first. `updated` is RFC 3339, so it sorts
     *  lexicographically — no date parsing needed. */
    fun recent(records: List<EntityRecord>, limit: Int = RECENT_LIMIT): List<EntityRecord> =
        records.sortedByDescending { it.updated }.take(limit)

    /** Case-insensitive substring match over the label and a note's body. */
    fun search(records: List<EntityRecord>, query: String): List<EntityRecord> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return records.filter { rec ->
            labelOf(rec).lowercase().contains(q) ||
                rec.fields["body"]?.lowercase()?.contains(q) == true
        }.sortedByDescending { it.updated }
    }

    /**
     * What the screen shows: the recent few until the user types, then matches.
     * Search is not capped at [limit] — a deliberate query should show
     * everything it found, unlike the pre-typing peek at recent activity.
     */
    fun results(
        records: List<EntityRecord>,
        query: String,
        limit: Int = RECENT_LIMIT,
    ): List<EntityRecord> =
        if (query.isBlank()) recent(records, limit) else search(records, query)
}
