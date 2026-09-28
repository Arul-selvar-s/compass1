package com.compass.diary.util

import com.compass.diary.data.repository.DriveSync
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide "pull the other phone's changes" step, used by MainActivity while the app is open. */
@Singleton
class AppSyncManager @Inject constructor(
    private val driveSync: DriveSync,
    private val prefs: PreferencesManager
) {
    suspend fun pull() {
        val account = prefs.googleAccount.first()
        val enabled = prefs.isAutoSyncEnabled.first()
        if (account.isNullOrBlank() || !enabled) return
        driveSync.downloadAndRestore()
    }
}
