package com.hubidubios.mobile.sync

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
 * Background sync (PLAN.md Phase 5). Triggers: app foreground, network change
 * to a known SSID, and this periodic job. Never poll aggressively — the
 * workstation may be asleep. The outbox is durable, so a failed run just
 * retries; nothing is lost.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        Sync.open(applicationContext).syncNow()
        Result.success()
    } catch (e: Exception) {
        Result.retry() // durable outbox; safe to retry later
    }

    companion object {
        private const val NAME = "hubidubios-periodic-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED) // home wifi
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
