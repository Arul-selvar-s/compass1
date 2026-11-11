package com.compass.diary.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Runs the song sorting in the background, with a progress notification, even when the app is closed. */
@HiltWorker
class SongSortWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val classifier: SongClassifier
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        SongSortNotifications.foreground(applicationContext, classifier.progress.value)

    override suspend fun doWork(): Result {
        // Android may refuse to start a foreground service from the background; then the work still
        // runs, just without the guarantee of the notification, and resumes later if it gets stopped.
        try { setForeground(getForegroundInfo()) } catch (e: Exception) { /* ignore */ }

        val summary = classifier.runPending { p ->
            try { setForeground(SongSortNotifications.foreground(applicationContext, p)) } catch (e: Exception) { /* ignore */ }
        }
        SongSortNotifications.finished(applicationContext, summary, classifier.progress.value)
        return when (summary.result) {
            SongClassifier.RunResult.FINISHED, SongClassifier.RunResult.BLOCKED -> Result.success()
            SongClassifier.RunResult.RETRY_LATER -> Result.retry()
        }
    }
}
