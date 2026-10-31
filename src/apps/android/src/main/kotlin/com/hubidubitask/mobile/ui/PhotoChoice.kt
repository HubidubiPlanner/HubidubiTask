package com.hubidubitask.mobile.ui

import android.content.Context

/**
 * What the Photo note widget does with a photo, chosen in Settings and kept
 * on this phone: the AI chosen in Settings writes out the text in it (or,
 * with none, what it shows); or None, and the photo is kept as a photo note.
 * With no AI set up, or when it can't read it, the photo is kept too.
 */
object PhotoChoice {
    const val AI = "ai"
    const val NONE = "none"
    val OPTIONS = listOf(AI to "AI", NONE to "None")

    private const val PREFS = "ui"
    private const val KEY = "photo"

    fun load(context: Context): String =
        if (prefs(context).getString(KEY, null) == NONE) NONE else AI

    fun save(context: Context, choice: String) {
        prefs(context).edit().putString(KEY, if (choice == NONE) NONE else AI).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
