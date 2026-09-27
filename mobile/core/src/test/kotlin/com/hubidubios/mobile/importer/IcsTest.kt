package com.hubidubios.mobile.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class IcsTest {

    private val zone = ZoneId.of("Europe/Warsaw")
    private val from = LocalDate.of(2026, 9, 26)
    private val until = LocalDate.of(2027, 9, 26)

    private fun cal(vararg events: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") +
            "END:VCALENDAR\r\n"

    private fun ev(vararg lines: String) =
        "BEGIN:VEVENT\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    private fun parse(text: String) = Ics.parse(text, from, until, zone)

    @Test
    fun `single timed event in UTC keeps its instant`() {
        val r = parse(cal(ev("UID:a", "SUMMARY:Dentist", "DTSTART:20261006T080000Z",
            "DTEND:20261006T090000Z")))
        val o = r.occurrences.single()
        assertEquals("a", o.uid)
        assertEquals("2026-10-06T08:00:00Z", o.dtstart)
        assertEquals("2026-10-06T09:00:00Z", o.dtend)
    }

    @Test
    fun `TZID time is converted to UTC`() {
        val o = parse(cal(ev("UID:b", "SUMMARY:Standup",
            "DTSTART;TZID=Europe/Warsaw:20261006T090000"))).occurrences.single()
        assertEquals("2026-10-06T07:00:00Z", o.dtstart)   // CEST = UTC+2
    }

    @Test
    fun `all-day event stays a date`() {
        val o = parse(cal(ev("UID:c", "SUMMARY:Holiday", "DTSTART;VALUE=DATE:20261101",
            "DTEND;VALUE=DATE:20261102"))).occurrences.single()
        assertTrue(o.allDay)
        assertEquals("2026-11-01", o.dtstart)
        assertEquals("2026-11-02", o.dtend)
    }

    @Test
    fun `events outside the window and cancelled ones are left out`() {
        val r = parse(cal(
            ev("UID:old", "DTSTART:20250101T100000Z"),
            ev("UID:far", "DTSTART:20280101T100000Z"),
            ev("UID:x", "STATUS:CANCELLED", "DTSTART:20261010T100000Z"),
        ))
        assertTrue(r.occurrences.isEmpty())
    }

    @Test
    fun `weekly rule with weekdays expands and keeps local time across DST`() {
        val r = parse(cal(ev("UID:w", "SUMMARY:Gym", "DTSTART;TZID=Europe/Warsaw:20260921T180000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6")))
        // Series started Mon 21 Sep: 21, 23, 28, 30 Sep, 5, 7 Oct — two are before the window.
        assertEquals(listOf("w#20260928", "w#20260930", "w#20261005", "w#20261007"),
            r.occurrences.map { it.uid })
        assertEquals(0, r.unsupportedRules)
    }

    @Test
    fun `DST switch keeps the wall-clock time`() {
        val r = parse(cal(ev("UID:d", "DTSTART;TZID=Europe/Warsaw:20261019T090000",
            "RRULE:FREQ=WEEKLY;COUNT=3")))
        // 19 Oct CEST (UTC+2) -> 07:00Z; 26 Oct and 2 Nov CET (UTC+1) -> 08:00Z.
        assertEquals(listOf("2026-10-19T07:00:00Z", "2026-10-26T08:00:00Z", "2026-11-02T08:00:00Z"),
            r.occurrences.map { it.dtstart })
    }

    @Test
    fun `monthly nth weekday and until`() {
        val r = parse(cal(ev("UID:m", "DTSTART:20261013T100000Z",
            "RRULE:FREQ=MONTHLY;BYDAY=2TU;UNTIL=20270101T000000Z")))
        assertEquals(listOf("2026-10-13", "2026-11-10", "2026-12-08"),
            r.occurrences.map { it.dtstart.take(10) })
    }

    @Test
    fun `yearly birthday and exdate`() {
        val r = parse(cal(ev("UID:y", "DTSTART;VALUE=DATE:20100315",
            "RRULE:FREQ=YEARLY", "EXDATE;VALUE=DATE:20270315")))
        assertTrue("excluded occurrence came through", r.occurrences.isEmpty())
    }

    @Test
    fun `an edited occurrence replaces its day in the series`() {
        val r = parse(cal(
            ev("UID:s", "SUMMARY:Sync", "DTSTART:20261001T100000Z", "RRULE:FREQ=DAILY;COUNT=3"),
            ev("UID:s", "SUMMARY:Sync (moved)", "RECURRENCE-ID:20261002T100000Z",
                "DTSTART:20261002T150000Z"),
        ))
        assertEquals(listOf("Sync", "Sync (moved)", "Sync"), r.occurrences.map { it.summary })
        assertEquals(3, r.occurrences.map { it.uid }.toSet().size)
    }

    @Test
    fun `unsupported rule keeps its first date and is counted`() {
        val r = parse(cal(ev("UID:u", "DTSTART:20261001T100000Z",
            "RRULE:FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR")))
        assertEquals(1, r.unsupportedRules)
        assertEquals(listOf("u#20261001"), r.occurrences.map { it.uid })
    }

    @Test
    fun `folded lines and escapes are read`() {
        val o = parse(cal("BEGIN:VEVENT\r\nUID:f\r\nSUMMARY:Lunch\\, then a long\r\n  walk\r\n" +
            "DTSTART:20261003T110000Z\r\nEND:VEVENT\r\n")).occurrences.single()
        assertEquals("Lunch, then a long walk", o.summary)
    }
}
