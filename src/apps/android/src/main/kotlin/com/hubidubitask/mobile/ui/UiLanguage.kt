package com.hubidubitask.mobile.ui

import android.content.Context
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.Language

/**
 * The language the app speaks, chosen in Settings. Until one is chosen it is
 * the phone's own language when the app speaks it, else English. Every entry
 * point (the app, the widget, the share menu, background work) applies it
 * before it shows or writes any words.
 */
object UiLanguage {
    private const val PREFS = "ui"
    private const val KEY = "language"

    fun load(context: Context): Language =
        Language.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
            ?: Language.system()

    fun apply(context: Context): Language = load(context).also { I18n.language = it }

    fun save(context: Context, language: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.code).apply()
        I18n.language = language
    }
}
