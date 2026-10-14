package com.hubidubitask.mobile.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.R

/**
 * "New note" among the app icon's shortcuts (a long press on it), from where
 * it can be dragged out onto the home screen as an icon of its own. Made by
 * the app rather than declared, so its name follows the language chosen in
 * Settings; published again whenever that changes, which renames a copy
 * already on the home screen too.
 */
object NoteShortcut {
    private const val ID = "new_note"

    fun publish(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(t("New note"))
            .setLongLabel(t("New note"))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_note))
            .setIntent(Intent(context, QuickNoteActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
