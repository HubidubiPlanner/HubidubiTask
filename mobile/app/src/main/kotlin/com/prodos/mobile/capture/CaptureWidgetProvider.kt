package com.prodos.mobile.capture

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.prodos.mobile.R

/** One-tap capture from the home screen. The tap opens the translucent
 *  capture activity directly — the main app is never launched. */
class CaptureWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val intent = Intent(context, WidgetCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(context.packageName, R.layout.widget_capture).apply {
                setOnClickPendingIntent(R.id.widget_button, pending)
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
