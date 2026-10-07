package com.hubidubitask.core.i18n

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The languages the apps speak, each named in itself for the Settings list. */
enum class Language(val code: String, val label: String, val locale: Locale) {
    EN("en", "English", Locale.ENGLISH),
    PL("pl", "Polski", Locale.forLanguageTag("pl")),
    DE("de", "Deutsch", Locale.GERMAN),
    FR("fr", "Français", Locale.FRENCH),
    ES("es", "Español", Locale.forLanguageTag("es"));

    companion object {
        fun of(code: String?): Language? = entries.firstOrNull { it.code == code }
        /** The system's language when the apps speak it, else English. */
        fun system(): Language = of(Locale.getDefault().language) ?: EN
    }
}

/**
 * Everything the apps say to a person, in the language chosen in Settings.
 *
 * The English text is the key: `t("Mark done")`, `t("Due %s", date)`. Each other
 * language has a table from that English to its own words (Polish.kt and the
 * rest); a text missing from a table falls back to English, and a test fails
 * the build when any `t("…")` in the apps has no entry. Dates and counts go
 * through [dayLong], [dayShort], [count] and the like, which know each
 * language's word order and plural forms.
 */
object I18n {
    @Volatile var language: Language = Language.EN

    private fun table(l: Language): Map<String, String> = when (l) {
        Language.EN -> emptyMap()
        Language.PL -> POLISH
        Language.DE -> GERMAN
        Language.FR -> FRENCH
        Language.ES -> SPANISH
    }

    /** All the English texts the apps translate, by language: for the completeness test. */
    fun tableFor(l: Language): Map<String, String> = table(l)

    fun t(en: String, vararg args: Any?): String {
        val s = table(language)[en] ?: en
        return if (args.isEmpty()) s else String.format(language.locale, s, *args)
    }

    // ---- dates, each language's way

    private fun fmt(pattern: String) = DateTimeFormatter.ofPattern(pattern, language.locale)
    private fun cap(s: String) = s.replaceFirstChar { it.titlecase(language.locale) }

    /** "Wednesday, 7 October", "Środa, 7 października", "Mittwoch, 7. Oktober". */
    fun dayLong(d: LocalDate): String = cap(d.format(fmt(when (language) {
        Language.DE -> "EEEE, d. MMMM"
        Language.FR -> "EEEE d MMMM"
        Language.ES -> "EEEE, d 'de' MMMM"
        else -> "EEEE, d MMMM"
    })))

    /** "Tue 6 Oct": a date beside an item. */
    fun dayShort(d: LocalDate): String = cap(d.format(fmt(when (language) {
        Language.DE -> "EEE, d. MMM"
        else -> "EEE d MMM"
    })))

    /** "6 Oct". */
    fun dayMonth(d: LocalDate): String = d.format(fmt(if (language == Language.DE) "d. MMM" else "d MMM"))

    /** "Wed 7": a day in a week's heading. */
    fun weekdayDay(d: LocalDate): String = cap(d.format(fmt(if (language == Language.DE) "EEE, d." else "EEE d")))

    /** "Wed": a day's initial in the week strip. */
    fun weekdayShort(d: LocalDate): String = cap(d.format(fmt("EEE")))

    /** "3–9 Oct", or "26 Sep – 2 Oct" across a month boundary. */
    fun span(start: LocalDate, end: LocalDate): String =
        if (start.month == end.month && start.year == end.year)
            (if (language == Language.DE) "${start.dayOfMonth}.–" else "${start.dayOfMonth}–") + dayMonth(end)
        else "${dayMonth(start)} – ${dayMonth(end)}"

    // ---- counts

    /** "1 note", "3 notes", "5 notatek": [n] with the word in its plural form. */
    fun count(n: Int, word: String): String {
        val forms = PLURALS[language]?.get(word) ?: PLURALS.getValue(Language.EN).getValue(word)
        val i = when (language) {
            Language.PL -> when {
                n == 1 -> 0
                n % 10 in 2..4 && n % 100 !in 12..14 -> 1
                else -> 2
            }
            Language.FR -> if (n == 0 || n == 1) 0 else 1
            else -> if (n == 1) 0 else 1
        }
        return "$n ${forms[minOf(i, forms.lastIndex)]}"
    }

    /** One form for 1, then the rest; Polish has a third for 5 and up (and 12–14). */
    private val PLURALS: Map<Language, Map<String, List<String>>> = mapOf(
        Language.EN to mapOf("note" to listOf("note", "notes"), "item" to listOf("item", "items"),
            "open" to listOf("open", "open")),
        Language.PL to mapOf("note" to listOf("notatka", "notatki", "notatek"),
            "item" to listOf("rzecz", "rzeczy", "rzeczy"), "open" to listOf("otwarte", "otwarte", "otwartych")),
        Language.DE to mapOf("note" to listOf("Notiz", "Notizen"), "item" to listOf("Eintrag", "Einträge"),
            "open" to listOf("offen", "offen")),
        Language.FR to mapOf("note" to listOf("note", "notes"), "item" to listOf("élément", "éléments"),
            "open" to listOf("ouverte", "ouvertes")),
        Language.ES to mapOf("note" to listOf("nota", "notas"), "item" to listOf("elemento", "elementos"),
            "open" to listOf("abierta", "abiertas")),
    )
}

/** `t("…")` without the object's name, for the apps' screens. */
fun t(en: String, vararg args: Any?): String = I18n.t(en, *args)
