package com.compass.diary.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.data.repository.DriveSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sorts every song into mood + era by what it MEANS, using the careful multi-step [SongAnalyzer].
 *
 * The work runs inside a WorkManager worker ([SongSortWorker]) with a foreground notification, so it
 * keeps going when the app is closed (Android keeps the notification up while it works) and
 * resumes by itself after a pause (no internet, Gemini's limit). Each finished song is saved at once
 * and pushed to Drive in small batches.
 */
@Singleton
class SongClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DiaryRepository,
    private val prefs: PreferencesManager,
    private val driveSync: DriveSync
) {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Estimated time left for the whole queue; null until a few songs have been timed. */
        val etaMs: Long? = null,
        val currentTitle: String? = null,
        /** Why sorting is waiting or stopped (rate limit, offline, bad key...). */
        val message: String? = null,
        val needsKey: Boolean = false
    )

    enum class RunResult { FINISHED, RETRY_LATER, BLOCKED }
    data class Summary(val result: RunResult, val sorted: Int, val total: Int)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .callTimeout(240, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()
    private val kicked = AtomicBoolean(false)

    /**
     * Makes sure the background worker is queued. Cheap when there is nothing to do.
     * [force] = "Sort now": also skips a waiting back-off timer (never interrupts a running worker).
     */
    fun kick(force: Boolean = false) {
        kicked.set(true)
        scope.launch {
            val wm = WorkManager.getInstance(context)
            val running = wm.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().any { it.state == WorkInfo.State.RUNNING }
            if (running) return@launch   // the running loop re-checks `kicked` when it finishes
            val request = OneTimeWorkRequestBuilder<SongSortWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(WORK_NAME, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Pushes the current database (including tags) to Drive if the user has sync switched on. */
    suspend fun syncNow() {
        val account = prefs.googleAccount.first()
        val enabled = prefs.isAutoSyncEnabled.first()
        if (!account.isNullOrBlank() && enabled) driveSync.uploadAll()
    }

    // ------------------------------------------------------------------------------------

    /** Called by the worker: sorts everything that still needs it. */
    suspend fun runPending(onUpdate: suspend (Progress) -> Unit = {}): Summary {
        var sorted = 0
        var lastTotal = 0

        suspend fun publish(p: Progress) {
            _progress.value = p
            onUpdate(p)
        }

        do {
            kicked.set(false)
            val key = prefs.anthropicApiKey.first()   // the app's Gemini key lives under this (legacy) name
            val queue = repo.getAllSongsForBackup()
                .filter { SongSortPolicy.needsSorting(it.categoryTier, it.categoryTries) }
                // songs without any tags first (new ones), then older results that get re-checked; newest first
                .sortedWith(compareBy<SongMessageEntity> { it.categories != null }.thenByDescending { it.sentAt })
            lastTotal = queue.size
            if (queue.isEmpty()) {
                publish(Progress())
                continue
            }
            if (key.isNullOrBlank()) {
                publish(Progress(total = queue.size, needsKey = true))
                return Summary(RunResult.BLOCKED, sorted, queue.size)
            }

            val api = HttpGeminiApi(key, http, MODELS) { msg -> _progress.value = _progress.value.copy(message = msg) }
            val analyzer = SongAnalyzer(api)
            val timings = ArrayDeque<Long>()
            var done = 0
            var unsynced = 0
            publish(Progress(running = true, total = queue.size))

            for (queued in queue) {
                currentCoroutineContext().ensureActive()
                // It may have been deleted, re-tagged by hand, or already sorted by the other phone meanwhile.
                val song = repo.getSongById(queued.id)
                if (song == null || !SongSortPolicy.needsSorting(song.categoryTier, song.categoryTries)) {
                    done++
                    publish(Progress(running = true, done = done, total = queue.size, etaMs = eta(timings, queue.size - done)))
                    continue
                }
                publish(Progress(running = true, done = done, total = queue.size, etaMs = eta(timings, queue.size - done), currentTitle = song.title))
                val started = System.currentTimeMillis()

                when (val a = analyzer.analyze(evidenceFor(song))) {
                    is Analysis.Done -> {
                        repo.setSongCategories(song.id, SongTags.serialize(a.verdict.tags), SongSortPolicy.TIER_DEEP)
                        sorted++
                        unsynced++
                    }
                    is Analysis.Failed -> repo.bumpSongCategoryTries(song.id)
                    Analysis.Offline -> return stop(
                        Summary(RunResult.RETRY_LATER, sorted, queue.size), unsynced,
                        Progress(done = done, total = queue.size, message = "No internet - sorting resumes by itself when you're back online"), onUpdate
                    )
                    is Analysis.RateLimited -> return stop(
                        Summary(RunResult.RETRY_LATER, sorted, queue.size), unsynced,
                        Progress(
                            done = done, total = queue.size,
                            message = if (a.daily) "Gemini's free daily limit is used up - sorting resumes by itself (usually tomorrow)"
                            else "Gemini's limit was reached - sorting resumes by itself shortly"
                        ), onUpdate
                    )
                    Analysis.BadKey -> return stop(
                        Summary(RunResult.BLOCKED, sorted, queue.size), unsynced,
                        Progress(total = queue.size, needsKey = true, message = "Gemini rejected the API key - check it in Settings"), onUpdate
                    )
                }
                done++
                timings.addLast(System.currentTimeMillis() - started)
                if (timings.size > ETA_WINDOW) timings.removeFirst()
                publish(Progress(running = true, done = done, total = queue.size, etaMs = eta(timings, queue.size - done)))
                if (unsynced >= UPLOAD_EVERY) {
                    syncNow()
                    unsynced = 0
                }
            }
            if (unsynced > 0) syncNow()
            publish(Progress())
        } while (kicked.get())
        return Summary(RunResult.FINISHED, sorted, lastTotal)
    }

    private suspend fun stop(summary: Summary, unsynced: Int, final: Progress, onUpdate: suspend (Progress) -> Unit): Summary {
        if (unsynced > 0) syncNow()
        _progress.value = final
        onUpdate(final)
        return summary
    }

    private fun eta(timings: ArrayDeque<Long>, remaining: Int): Long? =
        if (timings.isEmpty() || remaining < 0) null else (timings.average() * remaining).toLong()

    private suspend fun evidenceFor(song: SongMessageEntity): SongEvidence {
        val details = YoutubeMetadataFetcher.fetchDetails(song.youtubeUrl)
        val title = song.title?.takeIf { it.isNotBlank() } ?: details?.title
        if (song.title.isNullOrBlank() && details?.title != null) repo.setSongTitle(song.id, details.title)
        return SongEvidence(
            title = title,
            channel = details?.channel,
            uploadDate = details?.uploadDate,
            description = details?.description,
            keywords = details?.keywords ?: emptyList(),
            note = song.note,
            videoUrl = YoutubeIds.canonicalUrl(song.youtubeUrl)
        )
    }

    private companion object {
        const val WORK_NAME = "song_sort"
        // Same models the AI assistant already uses.
        val MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
        const val UPLOAD_EVERY = 5
        const val ETA_WINDOW = 6
    }
}
