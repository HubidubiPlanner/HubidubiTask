package com.hubidubitask.mobile.ui

import android.content.Context
import com.hubidubitask.core.NewItemDue

/**
 * The due date new tasks and notes get ([NewItemDue]), kept on this phone.
 * Read here too by the widget and the share sheet, which run without the app.
 */
object NewDue {
    private const val PREFS = "ui"
    // Named when the choice covered notes alone; kept so a choice made then holds.
    private const val KEY = "note_due"

    fun load(context: Context): String = NewItemDue.of(prefs(context).getString(KEY, null))

    fun save(context: Context, choice: String) {
        prefs(context).edit().putString(KEY, NewItemDue.of(choice)).apply()
    }

    /** The date a task or note made now gets, or null. */
    fun date(context: Context): String? = NewItemDue.due(load(context))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
