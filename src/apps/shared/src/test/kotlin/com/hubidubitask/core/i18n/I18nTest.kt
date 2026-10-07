package com.hubidubitask.core.i18n

import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    @AfterTest fun english() { I18n.language = Language.EN }

    /** Every t("…") in the apps and the shared code, read from the sources themselves. */
    private fun texts(): Set<String> {
        val roots = listOf("src/main/kotlin", "../android/src/main/kotlin", "../desktop/src/main/kotlin").map(::File)
        val call = Regex("""(?:(?<![\w.])|(?<=i18n\.))t\("((?:[^"\\]|\\.)*)"""")
        val found = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" && "/core/i18n/" !in it.path }.toList() }
            .flatMap { f -> call.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue(found.size > 150, "the scan found only ${found.size} texts; are the source folders where the test expects?")
        // Labels from lists, translated where they are shown: tabs, ranges, periods, the agent, the feed kind.
        return found + setOf("Incoming", "Projects", "Feeds", "Settings", "Day", "Week", "Month", "Agent", "A web page") -
            setOf("SelfHostedAI", "OpenAI", "Claude")
    }

    @Test fun `every text the apps show has every translation`() {
        val all = texts()
        for (l in Language.entries - Language.EN) {
            val table = I18n.tableFor(l)
            val missing = all.filter { it !in table }
            assertTrue(missing.isEmpty(), "${l.label} misses ${missing.size}: ${missing.take(10)}")
        }
    }

    @Test fun `translations keep the English placeholders, in order`() {
        val spec = Regex("%[sd]")
        for (l in Language.entries - Language.EN) for ((en, tr) in I18n.tableFor(l)) {
            assertEquals(spec.findAll(en).map { it.value }.toList(), spec.findAll(tr).map { it.value }.toList(),
                "${l.label}: \"$tr\" for \"$en\"")
        }
    }

    @Test fun `texts and dates follow the chosen language`() {
        val d = LocalDate.of(2026, 10, 7)
        val expected = mapOf(
            Language.EN to listOf("Incoming", "Wednesday, 7 October", "Due Wed 7 Oct"),
            Language.PL to listOf("Nadchodzące", "Środa, 7 października", "Termin: Śr. 7 paź"),
            Language.DE to listOf("Anstehend", "Mittwoch, 7. Oktober", "Fällig Mi., 7. Okt."),
            Language.FR to listOf("À venir", "Mercredi 7 octobre", "Échéance Mer. 7 oct."),
            Language.ES to listOf("Próximos", "Miércoles, 7 de octubre", "Vence Mié 7 oct"),
        )
        for ((l, want) in expected) {
            I18n.language = l
            assertEquals(want[0], t("Incoming"), l.label)
            assertEquals(want[1], I18n.dayLong(d), l.label)
            // Short month and day names come from the Java runtime; compare without trailing dots.
            assertEquals(want[2].replace(".", ""), t("Due %s", I18n.dayShort(d)).replace(".", ""), l.label)
        }
    }

    @Test fun `counts use each language's plural forms`() {
        I18n.language = Language.PL
        assertEquals(listOf("1 notatka", "2 notatki", "5 notatek", "12 notatek", "22 notatki"),
            listOf(1, 2, 5, 12, 22).map { I18n.count(it, "note") })
        I18n.language = Language.EN
        assertEquals(listOf("1 note", "3 notes"), listOf(1, 3).map { I18n.count(it, "note") })
        I18n.language = Language.FR
        assertEquals(listOf("0 note", "1 note", "2 notes"), listOf(0, 1, 2).map { I18n.count(it, "note") })
    }

    @Test fun `the AI is asked to answer in the chosen language`() {
        val task = com.hubidubitask.core.EntityRecord("t", "next_action", "2026-10-07T10:00:00Z", mapOf("text" to "Call Ana"))
        assertTrue("Answer in" !in com.hubidubitask.mobile.browse.ReviewBrowse.analysisPrompt(task))
        I18n.language = Language.PL
        assertTrue("Answer in Polish." in com.hubidubitask.mobile.browse.ReviewBrowse.analysisPrompt(task))
    }

    @Test fun `the system language is used until one is chosen, English when the apps do not speak it`() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pl-PL"))
            assertEquals(Language.PL, Language.system())
            java.util.Locale.setDefault(java.util.Locale.JAPANESE)
            assertEquals(Language.EN, Language.system())
        } finally { java.util.Locale.setDefault(before) }
    }
}
