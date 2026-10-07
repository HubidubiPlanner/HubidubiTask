package com.hubidubitask.mobile.browse

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.t

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
            Bucket.OVERDUE -> t("Overdue")
            Bucket.TODAY -> t("Today")
            Bucket.TOMORROW -> t("Tomorrow")
            Bucket.LATER -> t("Later")
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

    /**
     * How far ahead Next looks. Rolling from today rather than calendar
     * weeks and months: on a Saturday "this week" would otherwise be one day.
     */
    enum class Range(val days: Int, val label: String) {
        DAY(1, "Day"), WEEK(7, "Week"), MONTH(30, "Month")
    }

    enum class Tone { OVERDUE, TODAY, SOON, LATER }

    data class Group(val title: String, val tone: Tone, val items: List<EntityRecord>)

    /**
     * What one range shows: overdue first whatever the range (a late item is
     * late in every view), then the range's own grouping — the day itself,
     * each day of the week, or each week of the month. Empty groups are left
     * out. [laterThisWeek] counts what the Day view hides in the rest of the
     * week, so it can say so instead of letting tomorrow go unseen.
     */
    data class View(val groups: List<Group>, val laterThisWeek: Int)

    private val ISO = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

    private fun parse(iso: String) = runCatching { java.time.LocalDate.parse(iso, ISO) }.getOrNull()

    fun view(records: List<EntityRecord>, today: String, range: Range): View {
        val day0 = parse(today) ?: return View(emptyList(), 0)
        val dated = records
            .mapNotNull { r -> r.fields["due"]?.let(::parse)?.let { it to r } }
            .sortedBy { it.first }
        val overdue = dated.filter { it.first < day0 }.map { it.second }
        val last = day0.plusDays(range.days - 1L)
        val inRange = dated.filter { it.first >= day0 && it.first <= last }

        val groups = mutableListOf<Group>()
        if (overdue.isNotEmpty()) groups += Group(t("Overdue"), Tone.OVERDUE, overdue)
        when (range) {
            Range.DAY, Range.WEEK -> inRange.groupBy { it.first }.forEach { (d, xs) ->
                val title = if (d == day0) {
                    if (range == Range.DAY) t("Today") else t("Today · %s", I18n.weekdayDay(d))
                } else if (d.month == day0.month) I18n.weekdayDay(d) else I18n.dayShort(d)
                groups += Group(title, if (d == day0) Tone.TODAY else Tone.SOON, xs.map { it.second })
            }
            Range.MONTH -> {
                // Seven-day stretches from today, the same rolling week the
                // Week view shows, so "This week" means the same thing in both.
                inRange.groupBy {
                    java.time.temporal.ChronoUnit.DAYS.between(day0, it.first) / 7
                }.forEach { (k, xs) ->
                    val span = weekSpan(day0.plusDays(7 * k))
                    val (title, tone) = when (k) {
                        0L -> t("This week") to Tone.TODAY
                        1L -> t("Next week · %s", span) to Tone.SOON
                        else -> span to Tone.LATER
                    }
                    groups += Group(title, tone, xs.map { it.second })
                }
            }
        }
        val weekEnd = day0.plusDays(6)
        val laterThisWeek = if (range == Range.DAY)
            dated.count { it.first > day0 && it.first <= weekEnd } else 0
        return View(groups, laterThisWeek)
    }

    /** "3–9 Oct", or "26 Sep – 2 Oct" across a month boundary. */
    fun weekSpan(start: java.time.LocalDate): String {
        val end = start.plusDays(6)
        return I18n.span(start, end)
    }

    /** The next [days] days from [today] with how many items fall on each: the Week strip. */
    fun counts(records: List<EntityRecord>, today: String, days: Int = 7): List<Pair<java.time.LocalDate, Int>> {
        val day0 = parse(today) ?: return emptyList()
        val dues = records.mapNotNull { it.fields["due"]?.let(::parse) }.groupingBy { it }.eachCount()
        return (0 until days).map { i -> day0.plusDays(i.toLong()).let { it to (dues[it] ?: 0) } }
    }
}
