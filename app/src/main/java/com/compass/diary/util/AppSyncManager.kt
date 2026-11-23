package com.compass.diary.util

import android.content.Context
import com.compass.diary.data.repository.DriveSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that keeps this phone and Drive in step while the app is open.
 *
 *  - [pull]: look for the other phone's changes (a tiny request; the backup is only downloaded when it changed).
 *  - [requestPush]: "something changed here, upload it soon". Runs in the app's own scope, so it is
 *    not lost when the screen that made the change closes, and it keeps retrying (offline, Drive hiccup).
 *    WorkManager is the safety net if Android kills the app before the upload happened.
 */
@Singleton
class AppSyncManager @Inject constructor(
    private val driveSync: DriveSync,
    private val prefs: PreferencesManager,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pushJob: Job? = null

    private suspend fun enabled(): Boolean {
        val account = prefs.googleAccount.first()
        return !account.isNullOrBlank() && prefs.isAutoSyncEnabled.first()
    }

    /** Takes in the other phone's changes. [force] = download even if the backup looks unchanged. */
    suspend fun pull(force: Boolean = false) {
        if (!enabled()) return
        driveSync.downloadAndRestore(force)
    }

    /** App opened: take new changes in, then send anything that has not been uploaded yet. */
    suspend fun syncOnOpen() {
        if (!enabled()) return
        driveSync.downloadAndRestore()
        driveSync.uploadAll(onlyIfChanged = true)
    }

    /** Local data changed: upload it in a moment (several quick changes become one upload). */
    fun requestPush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            var wait = 5_000L
            while (enabled()) {
                if (driveSync.uploadAll(onlyIfChanged = true).isSuccess) break
                delay(wait)
                wait = (wait * 2).coerceAtMost(120_000L)
            }
        }
        SyncScheduler.requestImmediateSync(context)
    }

    private companion object {
        const val PUSH_DELAY_MS = 2_000L
    }
}
