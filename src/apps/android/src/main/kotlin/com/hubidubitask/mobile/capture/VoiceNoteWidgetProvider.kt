package com.hubidubitask.mobile.capture

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.hubidubitask.mobile.R

/** "Voice note" on the home screen: one cell that looks like the app's icon with
 *  a note of music above the H, and opens [VoiceNoteActivity]. */
class VoiceNoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = PendingIntent.getActivity(
            context, 2, Intent(context, VoiceNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.widget_voice_note).apply {
            setOnClickPendingIntent(R.id.widget_voice_note, pending)
        }
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
    }
}
