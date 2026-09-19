package com.compass.diary.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "media_playback"
        private const val NOTIFICATION_ID = 9001
        const val ACTION_PLAY_PAUSE = "com.compass.diary.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.compass.diary.action.NEXT"
        const val ACTION_PREVIOUS = "com.compass.diary.action.PREVIOUS"
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private var mediaSession: MediaSessionCompat? = null

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun getOrCreateSession(): MediaSessionCompat {
        return mediaSession ?: MediaSessionCompat(context, "CompassMediaSession").apply {
            isActive = true
            mediaSession = this
        }
    }

    fun show(title: String, subtitle: String, isPlaying: Boolean) {
        ensureChannel()
        val session = getOrCreateSession()
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                .build()
        )

        val playPauseIntent = actionPendingIntent(ACTION_PLAY_PAUSE)
        val nextIntent = actionPendingIntent(ACTION_NEXT)
        val prevIntent = actionPendingIntent(ACTION_PREVIOUS)

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
        mediaSession?.isActive = false
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
