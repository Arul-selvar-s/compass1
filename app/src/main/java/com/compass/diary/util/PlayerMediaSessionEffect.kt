package com.compass.diary.util

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle

/**
 * Registers a media session while the Player screen is open, so the buttons on
 * wired/Bluetooth headphones (and car/watch controls) can play, pause, skip and go back.
 *
 * Headphone button behaviour (handled by the system's default media-button logic):
 *   single press  -> play / pause
 *   double press  -> next song
 *   triple press  -> previous song
 * Dedicated next/previous keys on headphones work directly.
 *
 * Commands are only acted on while the app is on screen. When the phone is locked the
 * song is paused by the Player (YouTube doesn't allow embedded playback in the background),
 * so a headphone "play" press then is ignored instead of leaving the app out of sync.
 */
@Composable
fun PlayerMediaSessionEffect(
    title: String,
    artist: String,
    hasSong: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val playRef = rememberUpdatedState(onPlay)
    val pauseRef = rememberUpdatedState(onPause)
    val nextRef = rememberUpdatedState(onNext)
    val prevRef = rememberUpdatedState(onPrevious)
    val seekRef = rememberUpdatedState(onSeekTo)
    val positionRef = rememberUpdatedState(positionMs)

    val session = remember {
        MediaSessionCompat(context, "CompassPlayerSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                private fun inForeground() =
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

                override fun onPlay() { if (inForeground()) playRef.value() }
                override fun onPause() { pauseRef.value() }
                override fun onSkipToNext() { if (inForeground()) nextRef.value() }
                override fun onSkipToPrevious() { if (inForeground()) prevRef.value() }
                override fun onSeekTo(pos: Long) { if (inForeground()) seekRef.value(pos) }
            })
            isActive = true
        }
    }

    DisposableEffect(session) {
        onDispose {
            session.isActive = false
            session.release()
        }
    }

    // Song info (also shows on Bluetooth car displays / watches).
    LaunchedEffect(title, artist, durationMs, hasSong) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build()
        )
    }

    // Play/pause state + which buttons are allowed. Refreshed every few seconds so the
    // position the system shows stays close to the real one.
    LaunchedEffect(isPlaying, hasSong, positionMs / 3000) {
        val state = when {
            !hasSong -> PlaybackStateCompat.STATE_NONE
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, positionRef.value, if (isPlaying) 1f else 0f)
                .build()
        )
    }
}
