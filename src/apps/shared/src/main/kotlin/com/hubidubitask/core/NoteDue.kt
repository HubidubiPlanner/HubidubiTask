package com.hubidubitask.core

import java.time.LocalDate

/**
 * When a new note is due, as chosen in Settings. Tomorrow, the default, brings
 * it back on Incoming the next day, so a thought put down in passing is looked
 * at again; or no date, and it waits in its project until it is looked for.
 */
object NoteDue {
    const val TOMORROW = "tomorrow"
    const val NONE = "none"

    /** The choices, each with the label Settings shows for it. */
    val OPTIONS = listOf(TOMORROW to "Tomorrow", NONE to "No date")

    /** A stored choice read back; anything unknown or missing is the default. */
    fun of(stored: String?): String = if (stored == NONE) NONE else TOMORROW

    /** The due date a new note gets under [choice], or null for none. */
    fun due(choice: String?, today: LocalDate = LocalDate.now()): String? =
        if (of(choice) == TOMORROW) today.plusDays(1).toString() else null
}
