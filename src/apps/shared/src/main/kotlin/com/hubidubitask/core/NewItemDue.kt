package com.hubidubitask.core

import java.time.LocalDate

/**
 * The due date every new task and note gets, as chosen in Settings; nothing
 * is asked when one is made. Tomorrow, the default, brings it back on Incoming
 * the next day, so what was put down in passing is looked at again. Next week
 * is seven days on, the first day under "Next week" in the Month view, whose
 * weeks roll from today. No date, and it waits where it was filed. A long
 * press dates it differently.
 */
object NewItemDue {
    const val TOMORROW = "tomorrow"
    const val NEXT_WEEK = "next_week"
    const val NONE = "none"

    /** The choices, each with the label Settings shows for it. */
    val OPTIONS = listOf(TOMORROW to "Tomorrow", NEXT_WEEK to "Next week", NONE to "No date")

    /** A stored choice read back; anything unknown or missing is the default. */
    fun of(stored: String?): String = when (stored) {
        NEXT_WEEK -> NEXT_WEEK
        NONE -> NONE
        else -> TOMORROW
    }

    /** The due date a new item gets under [choice], or null for none. */
    fun due(choice: String?, today: LocalDate = LocalDate.now()): String? = when (of(choice)) {
        TOMORROW -> today.plusDays(1).toString()
        NEXT_WEEK -> today.plusDays(7).toString()
        else -> null
    }
}
