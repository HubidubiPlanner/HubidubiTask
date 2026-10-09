package com.hubidubitask.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.feed.Feeds
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.core.nowUtc
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test

/**
 * The desktop screenshots for the website: the app at its window size, at 2x,
 * light and dark, filled with the same made-up data as the phone's store
 * screenshots (DemoSeed). Runs only with SHOTS_DIR set:
 *
 *     SHOTS_DIR=… ./gradlew :desktop:test --tests '*DesktopShotsTest*'
 */
class DesktopShotsTest {
    private val w = 1080
    private val h = 720
    private val scale = 2f

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `render the website's desktop screenshots`() {
        val out = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        out.mkdirs()
        val store = Store(Files.createTempDirectory("hubi-shots").toFile())
        seed(store)
        val vm = HubidubiTaskViewModel(store, PeerServer(store, "Demo Mac") {})
        vm.setLanguage(Language.EN)
        repeat(100) { if (vm.state.value.projects.size < 4) Thread.sleep(100) }

        // What to click before the shot, in window points: Week on Incoming;
        // two projects opened on Projects.
        val shots = listOf(
            Triple("desktop-incoming", "next", listOf(Offset(586f, 118f))),
            // Lower one first, so opening it does not move the other.
            Triple("desktop-projects", "projects", listOf(Offset(400f, 274f), Offset(400f, 160f))),
        )
        for (dark in listOf(false, true)) for ((name, tab, clicks) in shots) {
            // A clicked header keeps keyboard focus, drawn as a tint; let go of it before the shot.
            var letGo by mutableStateOf(false)
            val scene = ImageComposeScene((w * scale).toInt(), (h * scale).toInt(), Density(scale)) {
                App(vm, tab, dark)
                val focus = LocalFocusManager.current
                LaunchedEffect(letGo) { if (letGo) focus.clearFocus(force = true) }
            }
            var t = 0L
            fun frame() { t += 16_000_000L; scene.render(t) }
            repeat(5) { frame() }
            for (p in clicks) {
                val at = Offset(p.x * scale, p.y * scale)
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
                repeat(30) { frame() }
            }
            // Away from everything, so no row is drawn hovered.
            scene.sendPointerEvent(PointerEventType.Move, Offset(5f, (h - 5) * scale))
            letGo = true
            repeat(90) { frame() }
            val img = scene.render(t + 500_000_000L)
            File(out, name + (if (dark) "-dark" else "") + ".png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }

    /** DemoSeed's data, written the way the desktop app writes it. */
    private fun seed(store: Store) {
        val today = LocalDate.now()
        fun day(offset: Long) = today.plusDays(offset).toString()
        var n = 0
        fun put(type: String, prefix: String, fields: Map<String, String?>): String {
            val id = "${prefix}_demo${n++}"
            store.put(EntityRecord(id, type, nowUtc(), fields.filterValues { it != null }))
            return id
        }
        fun project(title: String, tasks: List<Pair<String, Long?>>): Pair<String, List<String>> {
            val id = put("project", "p", mapOf("title" to title, "status" to "active"))
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return id to tasks.map { (text, due) ->
                put("next_action", "t", mapOf("text" to text, "parent" to id, "project" to slug, "due" to due?.let(::day)))
            }
        }
        val (_, kitchen) = project("Kitchen renovation", listOf(
            "Call the tiler for a quote" to -1L, "Pick cabinet handles" to 0L, "Order the worktop" to 3L))
        put("note", "n", mapOf("title" to "Worktop measurements", "kind" to "text",
            "body" to "Wall 312 cm. Window starts 120 cm from the left.", "parent" to kitchen[2]))
        val (lisbon, _) = project("Trip to Lisbon", listOf(
            "Book flights" to 0L, "Reserve a table at Cervejaria Ramiro" to 5L, "Renew passport" to 12L))
        put("note", "n", mapOf("title" to "Places to see", "kind" to "text",
            "body" to "Alfama at sunset. LX Factory on Sunday. Tram 28 early.", "parent" to lisbon))
        project("Q4 report", listOf(
            "Ask Ana for the sales numbers" to 0L, "Draft the summary" to 1L, "Send it to the board" to 20L))
        project("Garden", listOf("Plant the tulip bulbs" to 6L, "Fix the hose" to null))
        put("inbox_item", "i", mapOf("text" to "Look into a standing desk", "date" to day(0), "time" to "08:10"))

        fun at(offset: Long, hour: Int, minute: Int) =
            ZonedDateTime.of(today.plusDays(offset).atTime(hour, minute), ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().toString() + ":00Z"
        fun event(uid: String, summary: String, start: String, end: String, calendar: String, location: String?) =
            put("event", "e", mapOf("uid" to uid, "summary" to summary, "dtstart" to start, "dtend" to end,
                "calendar" to calendar, "all_day" to "false", "location" to location))
        event("demo-standup", "Team stand-up", at(0, 9, 30), at(0, 9, 45), "Work", null)
        event("demo-dinner", "Dinner with Marta", at(2, 19, 0), at(2, 21, 0), "Home", "Ramiro")
        event("demo-dentist", "Dentist", at(4, 8, 15), at(4, 9, 0), "Home", null)

        fun feed(title: String, url: String, period: String, project: String?, changed: Long?) =
            put(Feeds.TYPE, "f", mapOf("kind" to Feeds.KIND_WEB, "url" to url, "title" to title, "period" to period,
                "model" to "claude", "project" to project, "last_checked" to day(0) + "T07:00:00Z",
                "last_period" to Feeds.periodKey(period, today), "last_changed" to changed?.let(::day)))
        feed("Lisbon tram timetable", "https://www.carris.pt/en/", "week", lisbon, -2L)
        feed("Tiler's price list", "https://example.com/tiles/prices", "month", null, null)
        feed("Board meeting agenda", "https://example.com/board", "day", null, 0L)
    }
}
