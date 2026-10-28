package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Deletes history (and saved audio) older than the retention setting, once a day in the background. */
class HistoryCleanupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val removed = HistoryStore.get(applicationContext).prune(AppSettings(applicationContext).retentionDays)
        Log.i("HistoryCleanup", "Removed $removed old history entries")
        return Result.success()
    }

    companion object {
        private const val NAME = "history-cleanup"

        /** Idempotent: keeps the existing schedule if there is one. */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<HistoryCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
