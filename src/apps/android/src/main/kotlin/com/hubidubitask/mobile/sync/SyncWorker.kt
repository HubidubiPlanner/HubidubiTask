package com.hubidubitask.mobile.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background work, hourly on Wi-Fi: a sync round with the paired computer,
 * then the feeds whose period has turned. The app does the same every minute
 * while it is open. A failed round loses nothing: the change log keeps what
 * the computer has not seen yet.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        // Feeds write their messages in the app's language.
        com.hubidubitask.mobile.ui.UiLanguage.apply(applicationContext)
        runCatching { Sync.syncMac(applicationContext) }
        // Feeds whose day, week or month has turned; the tasks they make go
        // out with the next sync.
        runCatching { com.hubidubitask.mobile.feed.FeedWork.run(applicationContext) }
        Result.success()
    } catch (e: Exception) {
        Result.retry() // the change log keeps what the computer has not seen
    }

    companion object {
        private const val NAME = "hubidubitask-periodic-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED) // home wifi
                        .build()
                )
                .build()
            val wm = WorkManager.getInstance(context)
            // The job scheduled while the app was HubidubiOS points at a class
            // that no longer exists; without this it would keep failing hourly.
            wm.cancelUniqueWork("hubidubios-periodic-sync")
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
