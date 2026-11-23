package com.compass.diary.util

import org.json.JSONObject
import java.util.Calendar

/** One song waiting to be sorted, with everything known about it. */
data class BatchItem(val id: Long, val evidence: SongEvidence)

sealed interface BatchOutcome {
    /** [verdicts] has an entry for every song that got a usable answer; the others should be tried again later. */
    data class Answered(val verdicts: Map<Long, SongVerdict>) : BatchOutcome
    data class Failed(val reason: String) : BatchOutcome
    object Offline : BatchOutcome
    data class RateLimited(val daily: Boolean) : BatchOutcome
    object BadKey : BatchOutcome
}

/**
 * The FAST way to sort songs: many songs in ONE Gemini question (no web research, no listening to the
 * video, no second opinion). What it goes on: the video's title, channel, description and tags plus the
 * model's own knowledge of the song. A few seconds for a dozen songs instead of minutes per song.
 *
 * The era is still derived from a release year that passed the same sanity checks as before
 * ([SongVerdictBuilder]): never later than the upload, never invented.
 */
class SongBatchAnalyzer(
    private val api: GeminiApi,
    private val currentYear: () -> Int = { Calendar.getInstance().get(Calendar.YEAR) }
) {
    suspend fun analyze(items: List<BatchItem>): BatchOutcome {
        if (items.isEmpty()) return BatchOutcome.Answered(emptyMap())
        val verdicts = HashMap<Long, SongVerdict>()
        var pending = items
        var lastFailure = "the AI's answer could not be read"

        // Asked at most twice: the second time only about the songs the first answer skipped.
        for (attempt in 0 until 2) {
            if (pending.isEmpty()) break
            val request = GeminiRequest(
                parts = listOf(JSONObject().put("text", SongPrompts.fastBatch(pending.map { it.evidence }))),
                json = true,
                schema = SongPrompts.batchSchema(),
                thinkingBudget = THINKING_BUDGET
            )
            when (val r = api.generate(request)) {
                is GeminiResult.Text -> {
                    val parsed = BatchParser.parse(r.text)
                    val answered = HashSet<Long>()
                    pending.forEachIndexed { i, item ->
                        val judged = parsed[i + 1] ?: return@forEachIndexed
                        answered += item.id
                        SongVerdictBuilder.build(judged, null, item.evidence.uploadYear, currentYear())
                            ?.let { verdicts[item.id] = it }
                    }
                    pending = pending.filter { it.id !in answered }
                }
                is GeminiResult.Failed -> lastFailure = r.reason
                // A stop signal in the second round must not throw away what the first round got.
                GeminiResult.Offline -> if (verdicts.isEmpty()) return BatchOutcome.Offline else break
                is GeminiResult.RateLimited -> if (verdicts.isEmpty()) return BatchOutcome.RateLimited(r.daily) else break
                GeminiResult.BadKey -> if (verdicts.isEmpty()) return BatchOutcome.BadKey else break
            }
        }
        if (verdicts.isEmpty() && pending.size == items.size) return BatchOutcome.Failed(lastFailure)
        return BatchOutcome.Answered(verdicts)
    }

    private companion object {
        const val THINKING_BUDGET = 2048
    }
}
