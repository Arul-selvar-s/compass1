package com.compass.diary.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.compass.diary.data.repository.DriveSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/** Background sync (even when the app is closed): take in the other phone's changes, then upload ours. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val driveSync: DriveSync,
    private val prefs: PreferencesManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val account = prefs.googleAccount.first()
        val enabled = prefs.isAutoSyncEnabled.first()
        if (account.isNullOrBlank() || !enabled) return Result.success()

        // Pull first so an upload never overwrites what the other phone added; upload only if something changed.
        val pulled = driveSync.downloadAndRestore().isSuccess
        val pushed = driveSync.uploadAll(onlyIfChanged = true).isSuccess

        return when {
            pulled && pushed -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.success()   // the next periodic run tries again
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}
