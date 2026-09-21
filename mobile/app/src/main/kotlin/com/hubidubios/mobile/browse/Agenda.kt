package com.hubidubios.mobile.browse

import com.hubidubios.core.EntityRecord

/**
 * Next, grouped by when. A flat list sorted by date makes you read every row to
 * find where today ends; the headings do that reading for you.
 *
 * Overdue is separated rather than folded into Today, because "should already
 * have happened" and "happens today" call for different decisions.
 */
object Agenda {

    enum class Bucket { OVERDUE, TODAY, TOMORROW, LATER }

    data class Section(val bucket: Bucket, val items: List<EntityRecord>) {
        val title: String get() = when (bucket) {
            Bucket.OVERDUE -> "Overdue"
            Bucket.TODAY -> "Today"
            Bucket.TOMORROW -> "Tomorrow"
            Bucket.LATER -> "Later"
        }
    }

    /** ISO dates compare lexicographically, so no date parsing is needed. */
    fun bucketOf(due: String, today: String, tomorrow: String): Bucket = when {
        due < today -> Bucket.OVERDUE
        due == today -> Bucket.TODAY
        due == tomorrow -> Bucket.TOMORROW
        else -> Bucket.LATER
    }

    /** The day after [today], as an ISO date. */
    fun dayAfter(today: String): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val d = fmt.parse(today) ?: return today
        return fmt.format(java.util.Date(d.time + 24L * 60 * 60 * 1000))
    }

    /**
     * Whether a date is close enough to be worth showing outside Next.
     *
     * Anything further out than tomorrow is noise in a list you are reading to
     * find things, not to plan the week — that is what Next is for. Overdue
     * counts as imminent: it is the most urgent thing a date can say.
     */
    fun isImminent(due: String?, today: String): Boolean {
        val d = due?.takeIf { it.isNotBlank() } ?: return false
        return bucketOf(d, today, dayAfter(today)) != Bucket.LATER
    }

    /**
     * Dated records as ordered sections, soonest first, empty buckets omitted —
     * an empty "Tomorrow" heading is furniture, not information.
     */
    fun sections(records: List<EntityRecord>, today: String): List<Section> {
        val tomorrow = dayAfter(today)
        val dated = records
            .filter { !it.fields["due"].isNullOrBlank() }
            .sortedBy { it.fields["due"] }
        return Bucket.entries.mapNotNull { bucket ->
            val items = dated.filter {
                bucketOf(it.fields["due"]!!, today, tomorrow) == bucket
            }
            if (items.isEmpty()) null else Section(bucket, items)
        }
    }
}
