package com.compass.diary.util

import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.data.repository.DriveSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sorts every song into mood + era sub-categories with Gemini, judging by what the song MEANS
 * (lyrics, feeling, style, release year) rather than by words in its title.
 *
 *  1. Ask Gemini about the song from its YouTube title + channel (+ your note).
 *  2. If it is not sure it knows the song, let Gemini actually watch/listen to the YouTube video.
 *  3. Save the tags on the song immediately, then push them to Drive.
 *
 * Runs in the background for the whole app, one song at a time at a gentle pace (free-tier friendly),
 * resumes where it stopped, and picks up new songs by itself.
 */
@Singleton
class SongClassifier @Inject constructor(
    private val repo: DiaryRepository,
    private val prefs: PreferencesManager,
    private val driveSync: DriveSync
) {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Why sorting stopped or is waiting (rate limit, offline, bad key...). */
        val message: String? = null,
        val needsKey: Boolean = false
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .callTimeout(150, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val kicked = AtomicBoolean(false)

    /** Call whenever songs may need sorting (app start, song added/edited, player opened). Cheap if nothing to do. */
    fun kick() {
        kicked.set(true)
        if (!running.compareAndSet(false, true)) return   // the running loop will notice `kicked`
        scope.launch {
            try {
                runLoop()
            } finally {
                running.set(false)
                if (kicked.get()) kick()
            }
        }
    }

    /** Pushes the current database (including tags) to Drive if the user has sync switched on. */
    suspend fun syncNow() {
        val account = prefs.googleAccount.first()
        val enabled = prefs.isAutoSyncEnabled.first()
        if (!account.isNullOrBlank() && enabled) driveSync.uploadAll()
    }

    // ------------------------------------------------------------------------------------

    private suspend fun runLoop() {
        do {
            kicked.set(false)
            val key = prefs.anthropicApiKey.first()   // the app's Gemini key lives under this (legacy) name
            val pending = repo.getAllSongsForBackup()
                .filter { it.categories == null && it.categoryTries < MAX_TRIES }
                .sortedByDescending { it.sentAt }     // newest songs first
            if (pending.isEmpty()) {
                _progress.value = Progress()
                continue
            }
            if (key.isNullOrBlank()) {
                _progress.value = Progress(total = pending.size, needsKey = true)
                return
            }

            var done = 0
            var sinceUpload = 0
            _progress.value = Progress(running = true, done = 0, total = pending.size)
            for (queued in pending) {
                // It may have been deleted, edited, or already sorted by the other phone meanwhile.
                val song = repo.getSongById(queued.id)
                if (song == null || song.categories != null) {
                    done++
                    _progress.value = Progress(running = true, done = done, total = pending.size)
                    continue
                }
                when (val outcome = classify(song, key)) {
                    is Outcome.Done -> {
                        repo.setSongCategories(song.id, SongTags.serialize(outcome.result.tags))
                        sinceUpload++
                    }
                    Outcome.Failed -> repo.bumpSongCategoryTries(song.id)
                    Outcome.Offline -> {
                        _progress.value = Progress(done = done, total = pending.size, message = "No internet - song sorting will continue when you're back online")
                        finish(sinceUpload)
                        return
                    }
                    Outcome.RateLimited -> {
                        _progress.value = Progress(done = done, total = pending.size, message = "Gemini's free limit was reached - sorting will continue later")
                        finish(sinceUpload)
                        return
                    }
                    Outcome.BadKey -> {
                        _progress.value = Progress(total = pending.size, needsKey = true, message = "Gemini rejected the API key - check it in Settings")
                        finish(sinceUpload)
                        return
                    }
                }
                done++
                _progress.value = Progress(running = true, done = done, total = pending.size)
                if (sinceUpload >= UPLOAD_EVERY) {
                    syncNow()
                    sinceUpload = 0
                }
                delay(PACE_MS)
            }
            finish(sinceUpload)
            _progress.value = Progress()
        } while (kicked.get())
    }

    private suspend fun finish(unsynced: Int) {
        if (unsynced > 0) syncNow()
    }

    // ------------------------------------------------------------------------------------

    private sealed interface Outcome {
        data class Done(val result: SongClassification) : Outcome
        object Failed : Outcome
        object Offline : Outcome
        object RateLimited : Outcome
        object BadKey : Outcome
    }

    private suspend fun classify(song: SongMessageEntity, key: String): Outcome {
        val info = YoutubeMetadataFetcher.fetchInfo(song.youtubeUrl)
        val title = song.title?.takeIf { it.isNotBlank() } ?: info?.title
        if (song.title.isNullOrBlank() && info?.title != null) repo.setSongTitle(song.id, info.title)

        var best: SongClassification? = null

        // Pass 1: Gemini recalls the song (lyrics, film, year) from its title + channel + note.
        if (!title.isNullOrBlank()) {
            val prompt = SongPrompt.build(title, info?.channel, song.note, withVideo = false)
            when (val g = generate(key, JSONArray().put(JSONObject().put("text", prompt)), lowRes = false)) {
                is Gen.Text -> best = SongClassificationParser.parseModelText(g.text)
                Gen.Offline -> return Outcome.Offline
                Gen.RateLimited -> return Outcome.RateLimited
                Gen.BadKey -> return Outcome.BadKey
                is Gen.Failed -> Unit
            }
            val firstAnswer = best
            if (firstAnswer != null && firstAnswer.isConfident) return Outcome.Done(firstAnswer)
        }

        // Pass 2: not sure it knows the song -> let Gemini actually watch/listen to the video.
        val videoUrl = YoutubeIds.canonicalUrl(song.youtubeUrl)
        if (videoUrl != null) {
            delay(PACE_MS)
            val prompt = SongPrompt.build(title, info?.channel, song.note, withVideo = true)
            val parts = JSONArray()
                .put(JSONObject().put("file_data", JSONObject().put("file_uri", videoUrl)))
                .put(JSONObject().put("text", prompt))
            when (val g = generate(key, parts, lowRes = true)) {
                is Gen.Text -> {
                    val fromVideo = SongClassificationParser.parseModelText(g.text)
                    if (fromVideo != null && fromVideo.moods.isNotEmpty()) return Outcome.Done(fromVideo)
                }
                Gen.Offline -> return Outcome.Offline
                Gen.RateLimited -> return Outcome.RateLimited
                Gen.BadKey -> return Outcome.BadKey
                is Gen.Failed -> Unit
            }
        }

        // Keep the unsure first answer rather than nothing at all.
        val fallback = best
        return if (fallback != null && fallback.moods.isNotEmpty()) Outcome.Done(fallback) else Outcome.Failed
    }

    // ------------------------------------------------------------------------------------
    // Gemini HTTP
    // ------------------------------------------------------------------------------------

    private sealed interface Gen {
        data class Text(val text: String) : Gen
        data class Failed(val reason: String) : Gen
        object Offline : Gen
        object RateLimited : Gen
        object BadKey : Gen
    }

    private sealed interface Call {
        data class Ok(val body: String) : Call
        data class Limited(val waitMs: Long) : Call
        object Overloaded : Call
        data class Rejected(val code: Int, val message: String) : Call
        object Offline : Call
    }

    private fun requestBody(parts: JSONArray, useSchema: Boolean, lowRes: Boolean): JSONObject {
        val config = JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json")
        if (useSchema) config.put("responseSchema", SongPrompt.schema())
        if (lowRes) config.put("mediaResolution", "MEDIA_RESOLUTION_LOW")   // far fewer tokens per video second
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config)
    }

    private suspend fun call(model: String, key: String, body: JSONObject): Call = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .addHeader("x-goog-api-key", key)
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful -> Call.Ok(text)
                    resp.code == 429 -> Call.Limited(SongClassificationParser.retryDelayMs(text))
                    resp.code == 503 || text.contains("overloaded", ignoreCase = true) -> Call.Overloaded
                    else -> Call.Rejected(resp.code, errorMessage(text))
                }
            }
        } catch (e: IOException) {
            Call.Offline
        }
    }

    private fun errorMessage(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message", "") ?: body.take(200)
    } catch (e: Exception) {
        body.take(200)
    }

    private suspend fun generate(key: String, parts: JSONArray, lowRes: Boolean): Gen {
        var useSchema = true
        var lowResOn = lowRes
        var modelIdx = 0
        var limitedTries = 0
        var busyTries = 0
        while (true) {
            val model = MODELS.getOrNull(modelIdx) ?: return Gen.Failed("no Gemini model accepted the request")
            when (val r = call(model, key, requestBody(parts, useSchema, lowResOn))) {
                is Call.Ok -> {
                    val text = SongClassificationParser.textOfResponse(r.body)
                    return if (text != null) Gen.Text(text) else Gen.Failed("empty or blocked answer")
                }
                is Call.Limited -> {
                    if (++limitedTries > MAX_RATE_RETRIES) return Gen.RateLimited
                    _progress.value = _progress.value.copy(message = "Waiting for Gemini's rate limit…")
                    delay(r.waitMs)
                    _progress.value = _progress.value.copy(message = null)
                }
                Call.Overloaded -> {
                    if (++busyTries > 2) { modelIdx++; busyTries = 0 }
                    delay(3_000L)
                }
                Call.Offline -> return Gen.Offline
                is Call.Rejected -> when {
                    r.message.contains("API key", ignoreCase = true) -> return Gen.BadKey
                    r.code == 400 && useSchema -> useSchema = false           // schema not accepted: rely on the prompt
                    r.code == 400 && lowResOn -> lowResOn = false             // mediaResolution not accepted
                    (r.code == 400 || r.code == 404) && modelIdx + 1 < MODELS.size -> modelIdx++
                    else -> return Gen.Failed("HTTP ${r.code}: ${r.message}")
                }
            }
        }
    }

    private companion object {
        // Same models the AI assistant already uses.
        val MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
        const val MAX_TRIES = 3
        const val MAX_RATE_RETRIES = 4
        const val UPLOAD_EVERY = 10
        const val PACE_MS = 4_500L   // ~13 requests a minute, inside the free tier
    }
}
