package com.compass.diary.util

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Calendar

sealed interface Analysis {
    data class Done(val verdict: SongVerdict, val log: String) : Analysis
    data class Failed(val reason: String) : Analysis
    object Offline : Analysis
    data class RateLimited(val daily: Boolean) : Analysis
    object BadKey : Analysis
}

/**
 * The deep, slow, careful way to sort one song. Quality over speed:
 *
 *  1. RESEARCH   Gemini searches the web: which song, official release date, what the lyrics are about.
 *  2. LISTEN     Gemini listens to / watches the YouTube video itself.
 *  3. JUDGE      Scores EVERY mood 0-10 and names the release year with its evidence.
 *  4. REVIEW     A strict second pass re-checks the judge against the evidence and corrects it.
 *  5. DECIDE     Our own code merges both opinions: moods from the averaged scores, era derived from a
 *                release year that passed sanity checks (never later than the upload, never invented).
 */
class SongAnalyzer(
    private val api: GeminiApi,
    private val pace: suspend () -> Unit = { delay(PACE_MS) },
    private val currentYear: () -> Int = { Calendar.getInstance().get(Calendar.YEAR) }
) {
    private fun text(s: String) = JSONObject().put("text", s)

    private fun GeminiResult.terminal(): Analysis? = when (this) {
        GeminiResult.Offline -> Analysis.Offline
        is GeminiResult.RateLimited -> Analysis.RateLimited(daily)
        GeminiResult.BadKey -> Analysis.BadKey
        else -> null
    }

    suspend fun analyze(e: SongEvidence): Analysis {
        val log = StringBuilder()

        // 1) research (web)
        var research: String? = null
        var webSearch = false
        when (val r = api.generate(GeminiRequest(listOf(text(SongPrompts.research(e))), grounded = true))) {
            is GeminiResult.Text -> {
                webSearch = r.grounded
                research = r.text + if (r.sources.isNotEmpty()) "\nSources consulted: ${r.sources.joinToString("; ")}" else ""
                log.append("research ok (web=$webSearch); ")
            }
            is GeminiResult.Failed -> log.append("research failed: ${r.reason}; ")
            else -> return r.terminal()!!
        }
        pace()

        // 2) listen to the video
        var listening: String? = null
        if (!e.videoUrl.isNullOrBlank()) {
            val parts = listOf(
                JSONObject().put("file_data", JSONObject().put("file_uri", e.videoUrl)),
                text(SongPrompts.listen(e))
            )
            when (val r = api.generate(GeminiRequest(parts, lowRes = true))) {
                is GeminiResult.Text -> { listening = r.text; log.append("listened; ") }
                is GeminiResult.Failed -> log.append("could not listen: ${r.reason}; ")
                else -> return r.terminal()!!
            }
            pace()
        }

        if (research == null && listening == null && e.title.isNullOrBlank() && e.description.isNullOrBlank()) {
            return Analysis.Failed("nothing is known about this song")
        }

        // 3) judge
        val judgePrompt = SongPrompts.judge(e, research, listening, webSearch)
        val judged = when (val j = ask(judgePrompt)) {
            is Asked.Parsed -> j
            is Asked.Stopped -> return j.analysis
            Asked.Unusable -> return Analysis.Failed("the AI's answer could not be read")
        }
        pace()

        // 4) review
        val reviewed: Asked.Parsed? = when (val r = ask(SongPrompts.review(e, research, listening, webSearch, judged.raw))) {
            is Asked.Parsed -> r
            is Asked.Stopped -> return r.analysis
            Asked.Unusable -> { log.append("review unreadable; "); null }
        }

        // 5) decide
        val verdict = SongVerdictBuilder.build(judged.song, reviewed?.song, e.uploadYear, currentYear())
            ?: return Analysis.Failed("no mood fits clearly")
        log.append("year=${verdict.releaseYear}; ${verdict.notes}")
        return Analysis.Done(verdict, log.toString())
    }

    private sealed interface Asked {
        data class Parsed(val song: JudgedSong, val raw: String) : Asked
        data class Stopped(val analysis: Analysis) : Asked
        object Unusable : Asked
    }

    /** One structured question, asked up to twice if the answer cannot be read. */
    private suspend fun ask(prompt: String): Asked {
        repeat(2) { attempt ->
            val r = api.generate(GeminiRequest(listOf(text(prompt)), json = true, schema = SongPrompts.judgeSchema()))
            when (r) {
                is GeminiResult.Text -> JudgeParser.parse(r.text)?.let { return Asked.Parsed(it, r.text) }
                is GeminiResult.Failed -> Unit
                else -> return Asked.Stopped(r.terminal()!!)
            }
            if (attempt == 0) pace()
        }
        return Asked.Unusable
    }

    companion object {
        const val PACE_MS = 6_000L   // keeps four calls per song inside Gemini's free per-minute limit
    }
}
