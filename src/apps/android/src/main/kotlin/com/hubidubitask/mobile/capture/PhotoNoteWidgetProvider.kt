package com.hubidubitask.mobile.capture

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.hubidubitask.mobile.R

/** "Photo note" on the home screen: one cell that looks like the app's icon with
 *  a picture above the H, and opens [PhotoNoteActivity]. */
class PhotoNoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = PendingIntent.getActivity(
            context, 3, Intent(context, PhotoNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.widget_photo_note).apply {
            setOnClickPendingIntent(R.id.widget_photo_note, pending)
        }
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
    }
}
