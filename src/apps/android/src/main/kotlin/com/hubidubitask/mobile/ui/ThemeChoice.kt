package com.hubidubitask.mobile.ui

import android.content.Context

/**
 * The colour theme chosen in Settings ([ColorTheme]), kept on this phone. Read
 * too by the quick note, which opens over the home screen without the app.
 */
object ThemeChoice {
    private const val PREFS = "ui"
    private const val KEY = "theme"

    fun load(context: Context): ColorTheme = ColorTheme.of(prefs(context).getString(KEY, null))

    fun save(context: Context, theme: ColorTheme) {
        prefs(context).edit().putString(KEY, theme.id).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
