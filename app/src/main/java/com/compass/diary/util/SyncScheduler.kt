package com.compass.diary.util

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC_WORK_NAME  = "hourly_drive_sync"
    private const val IMMEDIATE_WORK_NAME = "immediate_drive_sync"

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Background sync every 15 minutes (Android's minimum) while the app is closed. Name kept for callers. */
    fun scheduleHourlySync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun requestImmediateSync(context: Context) {
        // A few seconds' delay: while the app is open its own upload goes first and this one finds nothing left to do.
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(8, TimeUnit.SECONDS)
            .setConstraints(networkConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
