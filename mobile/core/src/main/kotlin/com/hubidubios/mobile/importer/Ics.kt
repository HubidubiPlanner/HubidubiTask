package com.hubidubios.mobile.importer

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Reads an iCalendar feed (Google's "secret address in iCal format") into the
 * single occurrences that fall in a window.
 *
 * The vault keeps one file per event and no recurrence rules, so a repeating
 * event is expanded here into one occurrence per day it happens, each with its
 * own UID (`<uid>#<yyyyMMdd>`). The common rules are covered — daily, weekly
 * (with weekdays), monthly (by day of month or "2nd Tuesday"), yearly, with
 * INTERVAL, COUNT, UNTIL and EXDATE. A rule using anything else keeps only its
 * first occurrence and is counted, so the import can say so.
 *
 * Pure (java.time only), so every rule is testable without a device.
 */
object Ics {

    data class Occurrence(
        val uid: String,
        val summary: String,
        /** "yyyy-MM-dd" for all-day, else UTC "yyyy-MM-dd'T'HH:mm:ss'Z'" — the vault's shapes. */
        val dtstart: String,
        val dtend: String?,
        val allDay: Boolean,
        val location: String?,
    )

    data class Result(val occurrences: List<Occurrence>, val unsupportedRules: Int)

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    /** A start that is either a whole day or a moment in a zone. */
    private data class Start(val day: LocalDate?, val at: ZonedDateTime?) {
        val allDay get() = day != null
        fun date(zone: ZoneId): LocalDate = day ?: at!!.withZoneSameInstant(zone).toLocalDate()
    }

    private val UTC_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val UID_DAY = DateTimeFormatter.BASIC_ISO_DATE

    fun parse(text: String, from: LocalDate, until: LocalDate, zone: ZoneId): Result {
        val events = blocks(unfold(text))
        // Edited single occurrences of a repeating event come as their own
        // VEVENT with RECURRENCE-ID; the series must skip that day.
        val overridden = HashMap<String, MutableSet<LocalDate>>()
        for (e in events) {
            val rid = e["RECURRENCE-ID"]?.firstOrNull() ?: continue
            val uid = e["UID"]?.firstOrNull()?.value ?: continue
            start(rid, zone)?.let { overridden.getOrPut(uid) { mutableSetOf() } += it.date(zone) }
        }

        val out = ArrayList<Occurrence>()
        var unsupported = 0
        for (e in events) {
            val uid = e["UID"]?.firstOrNull()?.value ?: continue
            if (e["STATUS"]?.firstOrNull()?.value.equals("CANCELLED", ignoreCase = true)) continue
            val start = e["DTSTART"]?.firstOrNull()?.let { start(it, zone) } ?: continue
            val end = e["DTEND"]?.firstOrNull()?.let { start(it, zone) }
            val summary = unescape(e["SUMMARY"]?.firstOrNull()?.value ?: "")
            val location = e["LOCATION"]?.firstOrNull()?.value?.let(::unescape)?.takeIf { it.isNotBlank() }
            val rid = e["RECURRENCE-ID"]?.firstOrNull()
            val rule = e["RRULE"]?.firstOrNull()?.value

            fun emit(s: Start, e2: Start?, id: String) {
                if (s.date(zone) < from || s.date(zone) > until) return
                out += Occurrence(id, summary, render(s), e2?.let(::render), s.allDay, location)
            }

            if (rid != null) {
                // An edited occurrence: its own UID so it doesn't clash with the series.
                val day = start(rid, zone)?.date(zone) ?: start.date(zone)
                emit(start, end, "$uid#${day.format(UID_DAY)}")
                continue
            }
            if (rule == null) {
                emit(start, end, uid)
                continue
            }
            val excluded = (e["EXDATE"].orEmpty().flatMap { p ->
                p.value.split(",").mapNotNull { v -> start(p.copy(value = v), zone)?.date(zone) }
            } + overridden[uid].orEmpty()).toSet()
            val (days, ok) = expand(rule, start, zone, until)
            if (!ok) unsupported++
            for (d in days) {
                if (d in excluded) continue
                val s = shift(start, d, zone)
                val e2 = end?.let { shiftBy(start, it, s) }
                emit(s, e2, "$uid#${d.format(UID_DAY)}")
            }
        }
        return Result(out.sortedBy { it.dtstart }, unsupported)
    }

    // ---- lines and blocks

    private fun unfold(text: String): List<String> {
        val lines = ArrayList<String>()
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty())
                lines[lines.lastIndex] = lines.last() + raw.substring(1)
            else lines += raw
        }
        return lines
    }

    private fun blocks(lines: List<String>): List<Map<String, List<Prop>>> {
        val out = ArrayList<Map<String, List<Prop>>>()
        var cur: HashMap<String, MutableList<Prop>>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> cur = HashMap()
                line == "END:VEVENT" -> { cur?.let { out += it }; cur = null }
                cur != null -> prop(line)?.let { cur.getOrPut(it.name) { mutableListOf() } += it }
            }
        }
        return out
    }

    private fun prop(line: String): Prop? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon).split(";")
        val params = head.drop(1).mapNotNull {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq).uppercase() to it.substring(eq + 1).trim('"') else null
        }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    // ---- times

    private fun start(p: Prop, zone: ZoneId): Start? = runCatching {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || v.length == 8)
            return@runCatching Start(LocalDate.parse(v.take(8), UID_DAY), null)
        val local = LocalDateTime.parse(v.removeSuffix("Z"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val z = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            p.params["TZID"] != null -> runCatching { ZoneId.of(p.params["TZID"]) }.getOrDefault(zone)
            else -> zone   // floating time: the device's own zone
        }
        Start(null, local.atZone(z))
    }.getOrNull()

    private fun render(s: Start): String =
        s.day?.toString() ?: UTC_OUT.format(s.at!!.toInstant())

    /** The series' start moved to [day], keeping its time of day in its own zone. */
    private fun shift(s: Start, day: LocalDate, zone: ZoneId): Start =
        if (s.allDay) Start(day, null)
        else {
            val local = s.at!!
            // `day` is in the device zone; map it back to the event's zone first.
            val offsetDays = ChronoUnit.DAYS.between(s.date(zone), day)
            Start(null, local.plusDays(offsetDays))
        }

    private fun shiftBy(origStart: Start, origEnd: Start, newStart: Start): Start =
        if (origStart.allDay && origEnd.allDay)
            Start(newStart.day!!.plusDays(ChronoUnit.DAYS.between(origStart.day, origEnd.day)), null)
        else if (!origStart.allDay && !origEnd.allDay)
            Start(null, newStart.at!!.plus(java.time.Duration.between(origStart.at, origEnd.at)))
        else origEnd

    // ---- recurrence

    private val DAYS = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY,
        "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

    private val SUPPORTED = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "WKST")

    /**
     * The days a rule produces, in the device zone, up to [until]. The Boolean
     * is false when the rule uses something not handled here; then only the
     * first occurrence is returned.
     */
    private fun expand(rule: String, start: Start, zone: ZoneId, until: LocalDate): Pair<List<LocalDate>, Boolean> {
        val r = rule.split(";").mapNotNull {
            val eq = it.indexOf('='); if (eq > 0) it.substring(0, eq).uppercase() to it.substring(eq + 1) else null
        }.toMap()
        val first = start.date(zone)
        val freq = r["FREQ"]
        val byDay = r["BYDAY"]?.split(",")?.map { it.trim() }.orEmpty()
        val byMonthDay = r["BYMONTHDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val ok = r.keys.all { it in SUPPORTED } &&
            freq in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") &&
            (byDay.isEmpty() || freq == "WEEKLY" || freq == "MONTHLY") &&
            (byMonthDay.isEmpty() || freq == "MONTHLY") &&
            (freq != "WEEKLY" || byDay.all { it in DAYS }) &&
            // Monthly weekdays need an ordinal ("2TU"); a bare "TU" means every
            // Tuesday of the month, which isn't handled.
            (freq != "MONTHLY" || byDay.all { Regex("^[+-]?\\d+[A-Z]{2}$").matches(it) })
        if (!ok) return listOf(first) to false

        val interval = r["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val ruleUntil = r["UNTIL"]?.let { u ->
            runCatching { start(Prop("UNTIL", emptyMap(), u), zone)?.date(zone) }.getOrNull()
        }
        val last = listOfNotNull(until, ruleUntil).min()

        val out = ArrayList<LocalDate>()
        var produced = 0
        fun take(d: LocalDate): Boolean {   // false = stop
            if (d < first) return true
            if (d > last || (count != null && produced >= count)) return false
            produced++; out += d; return true
        }
        var k = 0L
        loop@ while (k < 20_000) {
            when (freq) {
                "DAILY" -> if (!take(first.plusDays(k * interval))) break@loop
                "WEEKLY" -> {
                    val weekStart = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks(k * interval)
                    val dows = if (byDay.isEmpty()) listOf(first.dayOfWeek) else byDay.map { DAYS[it]!! }
                    for (d in dows.sorted().map { weekStart.plusDays((it.value - 1).toLong()) })
                        if (!take(d)) break@loop
                }
                "MONTHLY" -> {
                    val month = first.withDayOfMonth(1).plusMonths(k * interval)
                    val days = when {
                        byMonthDay.isNotEmpty() -> byMonthDay.mapNotNull { dayOfMonth(month, it) }
                        byDay.isNotEmpty() -> byDay.mapNotNull { nthWeekday(month, it) }
                        else -> listOfNotNull(dayOfMonth(month, first.dayOfMonth))
                    }
                    if (month > last) break@loop
                    for (d in days.sorted()) if (!take(d)) break@loop
                }
                "YEARLY" -> {
                    val y = first.year + k * interval
                    if (y > last.year) break@loop
                    // A 29 February start happens only in leap years.
                    val d = runCatching { LocalDate.of(y.toInt(), first.month, first.dayOfMonth) }.getOrNull()
                    if (d != null && !take(d)) break@loop
                }
            }
            k++
        }
        return out to true
    }

    private fun dayOfMonth(month: LocalDate, n: Int): LocalDate? {
        val len = month.lengthOfMonth()
        val day = if (n < 0) len + n + 1 else n
        return if (day in 1..len) month.withDayOfMonth(day) else null
    }

    /** "2TU" = second Tuesday, "-1FR" = last Friday, "MO" = every Monday isn't a single day. */
    private fun nthWeekday(month: LocalDate, spec: String): LocalDate? {
        val m = Regex("^([+-]?\\d+)?([A-Z]{2})$").find(spec) ?: return null
        val dow = DAYS[m.groupValues[2]] ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return if (n > 0) month.with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
            .takeIf { it.month == month.month }
        else month.with(TemporalAdjusters.lastInMonth(dow)).plusWeeks((n + 1).toLong())
            .takeIf { it.month == month.month }
    }

    /** Instant of a vault dtstart, for callers placing an event on a local day. */
    fun instantOf(dtstart: String): Instant? = runCatching { Instant.parse(dtstart) }.getOrNull()
}
