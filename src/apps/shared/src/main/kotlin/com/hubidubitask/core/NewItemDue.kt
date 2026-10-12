package com.hubidubitask.core

import java.time.LocalDate

/**
 * The due date every new task and note gets, as chosen in Settings; nothing
 * is asked when one is made. Tomorrow, the default, brings it back on Incoming
 * the next day, so what was put down in passing is looked at again; or no
 * date, and it waits where it was filed. A long press dates it differently.
 */
object NewItemDue {
    const val TOMORROW = "tomorrow"
    const val NONE = "none"

    /** The choices, each with the label Settings shows for it. */
    val OPTIONS = listOf(TOMORROW to "Tomorrow", NONE to "No date")

    /** A stored choice read back; anything unknown or missing is the default. */
    fun of(stored: String?): String = if (stored == NONE) NONE else TOMORROW

    /** The due date a new item gets under [choice], or null for none. */
    fun due(choice: String?, today: LocalDate = LocalDate.now()): String? =
        if (of(choice) == TOMORROW) today.plusDays(1).toString() else null
}
