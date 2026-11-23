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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sorts every song into mood + era by what it MEANS, the FAST way: songs go to Gemini in batches of
 * [BATCH_SIZE] ([SongBatchAnalyzer]), so a whole library takes minutes, not weeks.
 *
 * The work runs inside a WorkManager worker ([SongSortWorker]) with a foreground notification, so it
 * keeps going when the app is closed (Android keeps the notification up while it works) and
 * resumes by itself after a pause (no internet, Gemini's limit). Each finished batch is saved at once
 * and pushed to Drive in small groups.
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
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
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
            val analyzer = SongBatchAnalyzer(api)
            val timings = ArrayDeque<Long>()   // milliseconds per song, for the time-left estimate
            var done = 0
            var unsynced = 0
            publish(Progress(running = true, total = queue.size))

            for (chunk in queue.chunked(BATCH_SIZE)) {
                currentCoroutineContext().ensureActive()
                // Songs may have been deleted, re-tagged by hand, or already sorted by the other phone meanwhile.
                val songs = chunk.mapNotNull { q ->
                    repo.getSongById(q.id)?.takeIf { SongSortPolicy.needsSorting(it.categoryTier, it.categoryTries) }
                }
                done += chunk.size - songs.size
                if (songs.isEmpty()) {
                    publish(Progress(running = true, done = done, total = queue.size, etaMs = eta(timings, queue.size - done)))
                    continue
                }
                publish(Progress(running = true, done = done, total = queue.size, etaMs = eta(timings, queue.size - done), currentTitle = songs.first().title))
                val started = System.currentTimeMillis()

                // Title, channel, description and tags of the whole batch, fetched side by side.
                val evidence = gatherEvidence(songs)
                val usable = songs.indices.filter { hasSomethingToGoOn(evidence[it]) }
                // Songs with nothing known about them cannot be judged: count a try so they do not block the queue forever.
                songs.indices.filter { it !in usable }.forEach { repo.bumpSongCategoryTries(songs[it].id) }

                when (val out = analyzer.analyze(usable.map { BatchItem(songs[it].id, evidence[it]) })) {
                    is BatchOutcome.Answered -> usable.forEach { i ->
                        val song = songs[i]
                        val verdict = out.verdicts[song.id]
                        if (verdict != null) {
                            // TIER_DEEP simply means "sorted by the AI": it is what keeps a song out of the queue.
                            repo.setSongCategories(song.id, SongTags.serialize(verdict.tags), SongSortPolicy.TIER_DEEP)
                            sorted++
                            unsynced++
                        } else {
                            repo.bumpSongCategoryTries(song.id)
                        }
                    }
                    is BatchOutcome.Failed -> usable.forEach { repo.bumpSongCategoryTries(songs[it].id) }
                    BatchOutcome.Offline -> return stop(
                        Summary(RunResult.RETRY_LATER, sorted, queue.size), unsynced,
                        Progress(done = done, total = queue.size, message = "No internet - sorting resumes by itself when you're back online"), onUpdate
                    )
                    is BatchOutcome.RateLimited -> return stop(
                        Summary(RunResult.RETRY_LATER, sorted, queue.size), unsynced,
                        Progress(
                            done = done, total = queue.size,
                            message = if (out.daily) "Gemini's free daily limit is used up - sorting resumes by itself (usually tomorrow)"
                            else "Gemini's limit was reached - sorting resumes by itself shortly"
                        ), onUpdate
                    )
                    BatchOutcome.BadKey -> return stop(
                        Summary(RunResult.BLOCKED, sorted, queue.size), unsynced,
                        Progress(total = queue.size, needsKey = true, message = "Gemini rejected the API key - check it in Settings"), onUpdate
                    )
                }
                done += songs.size
                val perSong = (System.currentTimeMillis() - started) / songs.size
                repeat(songs.size.coerceAtMost(ETA_WINDOW)) {
                    timings.addLast(perSong)
                    if (timings.size > ETA_WINDOW) timings.removeFirst()
                }
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

    /** YouTube details for several songs at once (a few requests in parallel, in the order given). */
    private suspend fun gatherEvidence(songs: List<SongMessageEntity>): List<SongEvidence> = coroutineScope {
        val gate = Semaphore(FETCH_PARALLEL)
        songs.map { song -> async { gate.withPermit { evidenceFor(song) } } }.awaitAll()
    }

    private fun hasSomethingToGoOn(e: SongEvidence) = !e.title.isNullOrBlank() || !e.description.isNullOrBlank()

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
        const val BATCH_SIZE = 12       // songs per Gemini question
        const val FETCH_PARALLEL = 6    // YouTube page fetches at the same time
        const val UPLOAD_EVERY = 24     // push to Drive after this many newly sorted songs
        const val ETA_WINDOW = 12
    }
}
