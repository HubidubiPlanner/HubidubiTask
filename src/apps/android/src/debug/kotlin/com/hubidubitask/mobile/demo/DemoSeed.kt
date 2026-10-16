package com.hubidubitask.mobile.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hubidubitask.mobile.browse.ItemSummary
import com.hubidubitask.mobile.importer.Ics
import com.hubidubitask.mobile.sync.Sync
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Made-up data for store screenshots, dated around today so Next has an
 * overdue task, a full day and a busy week. Debug builds only:
 *
 *     adb shell am broadcast -a com.hubidubitask.mobile.DEMO_SEED -p <applicationId>
 *
 * Run it on an empty install; it adds, it does not replace.
 */
class DemoSeed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try { seed(Sync.open(context.applicationContext)) } finally { done.finish() }
        }.start()
    }

    private fun seed(s: Sync) {
        val today = LocalDate.now()
        fun day(offset: Long) = today.plusDays(offset).toString()

        fun project(title: String, tasks: List<Pair<String, Long?>>): Pair<String, List<String>> {
            val id = s.createProject(title)
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return id to tasks.map { (text, due) -> s.addNextAction(text, id, slug, due?.let(::day)) }
        }

        val (kitchen, kitchenTasks) = project("Kitchen renovation", listOf(
            "Call the tiler for a quote" to -1L,
            "Pick cabinet handles" to 0L,
            "Order the worktop" to 3L,
        ))
        s.noteText("Worktop measurements", "Wall 312 cm. Window starts 120 cm from the left.",
            parent = kitchenTasks[2])

        val (lisbon, _) = project("Trip to Lisbon", listOf(
            "Book flights" to 0L,
            "Reserve a table at Cervejaria Ramiro" to 5L,
            "Renew passport" to 12L,
        ))
        s.noteText("Places to see", "Alfama at sunset. LX Factory on Sunday. Tram 28 early.",
            parent = lisbon)

        project("Q4 report", listOf(
            "Ask Ana for the sales numbers" to 0L,
            "Draft the summary" to 1L,
            "Send it to the board" to 20L,
        ))
        project("Garden", listOf(
            "Plant the tulip bulbs" to 6L,
            "Fix the hose" to null,
        ))

        s.capture("Look into a standing desk")

        fun at(offset: Long, hour: Int, minute: Int) =
            ZonedDateTime.of(today.plusDays(offset).atTime(hour, minute), java.time.ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().toString() + ":00Z"
        s.addEvent(Ics.Occurrence("demo-standup", "Team stand-up", at(0, 9, 30), at(0, 9, 45),
            false, null), "Work")
        s.addEvent(Ics.Occurrence("demo-dinner", "Dinner with Marta", at(2, 19, 0), at(2, 21, 0),
            false, "Ramiro"), "Home")
        s.addEvent(Ics.Occurrence("demo-dentist", "Dentist", at(4, 8, 15), at(4, 9, 0),
            false, null), "Home")

        // Feeds, as if each had been looked at already.
        fun feed(title: String, url: String, period: String, project: String?, changed: Long?) =
            s.newFeed(mapOf("kind" to "web", "url" to url, "title" to title, "period" to period,
                "model" to "claude", "project" to project, "last_checked" to day(0) + "T07:00:00Z",
                // Looked at this period already, so the app does not fetch the made-up pages.
                "last_period" to com.hubidubitask.core.feed.Feeds.periodKey(period, today),
                "last_changed" to changed?.let(::day)))
        feed("Lisbon tram timetable", "https://www.carris.pt/en/", "week", lisbon, -2L)
        feed("Tiler's price list", "https://example.com/tiles/prices", "month", null, null)
        feed("Board meeting agenda", "https://example.com/board", "day", null, 0L)

        // Two summaries, as a review leaves them: kept on the item with the
        // fingerprint of what they read, so they show as up to date.
        fun review(id: String, text: String) {
            val all = listOf("project", "next_action", "inbox_item", "note").flatMap { s.byType(it) }
            val root = all.first { it.id == id }
            s.setReview(id, text, ItemSummary.fingerprint(root, ItemSummary.under(root, all)))
        }
        review(kitchenTasks[2], "Ready to order: the wall is 312 cm and the window starts 120 cm " +
            "from the left, as the note records. Nothing is ordered yet, and it is due in three days.")
        // Short enough to be read whole under the title, where four lines show.
        review(kitchen, "The worktop is measured and ready to order. The tiler's quote is a day " +
            "late, and the cabinet handles are being picked today.")
    }
}
