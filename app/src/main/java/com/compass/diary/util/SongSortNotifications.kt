package com.compass.diary.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/** The notification that stays visible while songs are being sorted in the background. */
object SongSortNotifications {
    private const val CHANNEL_ID = "song_sort"
    private const val RUNNING_ID = 4201
    private const val RESULT_ID = 4202

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Song sorting", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shows progress while your songs are sorted by mood and era" }
            )
        }
    }

    private fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progressText(p: SongClassifier.Progress): String = when {
        p.message != null -> p.message
        p.total == 0 -> "Getting ready…"
        else -> "${p.done} of ${p.total} songs" + (EtaFormat.text(p.etaMs)?.let { " · $it left" } ?: "")
    }

    fun foreground(context: Context, p: SongClassifier.Progress): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sorting your songs by meaning")
            .setContentText(progressText(p))
            .setSubText(p.currentTitle)
            .setProgress(p.total, p.done, p.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(RUNNING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(RUNNING_ID, notification)
        }
    }

    /** Last word when a run ends: done, or paused with the reason. */
    fun finished(context: Context, summary: SongClassifier.Summary, last: SongClassifier.Progress) {
        val text = when (summary.result) {
            SongClassifier.RunResult.FINISHED ->
                if (summary.sorted > 0) "All done - ${summary.sorted} song(s) sorted" else return
            SongClassifier.RunResult.RETRY_LATER ->
                (last.message ?: "Sorting paused") + " (${last.done} of ${last.total} done)"
            SongClassifier.RunResult.BLOCKED -> return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Song sorting")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification)
    }
}
