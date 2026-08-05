package com.compass.diary.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.compass.diary.data.repository.DriveSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

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

        val uploadOk = driveSync.uploadAll().isSuccess
        val downloadOk = driveSync.downloadAndRestore().isSuccess

        return if (uploadOk || downloadOk) Result.success() else Result.retry()
    }
}
