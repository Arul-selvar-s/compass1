package com.compass.diary.util

import org.json.JSONObject

/** Everything known about one song before asking the AI. */
data class SongEvidence(
    val title: String?,
    val channel: String?,
    /** yyyy-MM-dd the video was put on YouTube (NOT the song's release date). */
    val uploadDate: String?,
    val description: String?,
    val keywords: List<String>,
    val note: String?,
    val videoUrl: String?
) {
    val uploadYear: Int? get() = uploadDate?.take(4)?.toIntOrNull()
}

/** One model's judgement: release year plus a 0-10 score for every mood. */
data class JudgedSong(
    val releaseYear: Int?,
    val yearEvidence: String,
    val yearConfidence: Float,
    val summary: String,
    val scores: Map<SongTag, Float>,
    val reasoning: String
)

/** The final, validated result that gets saved on the song. */
data class SongVerdict(
    val moods: List<SongTag>,
    val era: SongTag?,
    val releaseYear: Int?,
    val summary: String,
    val notes: String
) {
    val tags: List<SongTag> get() = moods + listOfNotNull(era)
}

object JudgeParser {
    fun parse(text: String): JudgedSong? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(text.substring(start, end + 1))
            val scores = HashMap<SongTag, Float>()
            val raw = o.optJSONObject("mood_scores")
            if (raw != null) {
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val tag = SongTags.fromText(k)
                    if (tag != null && tag.group == SongTagGroup.MOOD) {
                        scores[tag] = raw.optDouble(k, 0.0).toFloat().coerceIn(0f, 10f)
                    }
                }
            }
            if (scores.isEmpty()) return null
            SongTags.moods.forEach { scores.putIfAbsent(it, 0f) }
            JudgedSong(
                releaseYear = yearOf(o.opt("release_year")),
                yearEvidence = o.optString("year_evidence", ""),
                yearConfidence = o.optDouble("year_confidence", 0.5).toFloat().coerceIn(0f, 1f),
                summary = o.optString("lyrics_summary", ""),
                scores = scores,
                reasoning = o.optString("reasoning", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun yearOf(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> Regex("""(19|20)\d\d""").find(v)?.value?.toIntOrNull()
        else -> null
    }
}

object SongVerdictBuilder {
    const val MOOD_THRESHOLD = 6f   // a mood counts when its (averaged) score is at least this
    const val MOOD_FLOOR = 4.5f     // if nothing reaches the threshold, the best mood is kept when at least this
    const val MAX_MOODS = 4

    /**
     * Combines the judge and the reviewer. Moods: the average score of both, so one model's slip cannot
     * decide alone. Era: derived from a release year that passed sanity checks.
     */
    fun build(judge: JudgedSong, review: JudgedSong?, uploadYear: Int?, currentYear: Int): SongVerdict? {
        val opinions = listOfNotNull(review, judge)   // reviewer first: wins ties
        val averaged = SongTags.moods.associateWith { tag ->
            opinions.map { it.scores[tag] ?: 0f }.average().toFloat()
        }
        val ranked = SongTags.moods.sortedByDescending { averaged[it] ?: 0f }
        var moods = ranked.filter { (averaged[it] ?: 0f) >= MOOD_THRESHOLD }.take(MAX_MOODS)
        if (moods.isEmpty()) {
            val best = ranked.first()
            if ((averaged[best] ?: 0f) >= MOOD_FLOOR) moods = listOf(best)
        }
        if (moods.isEmpty()) return null

        val notes = StringBuilder()
        // A release year must be plausible, and a song cannot be released after its video was uploaded.
        fun valid(y: Int) = y in 1931..(currentYear + 1) && (uploadYear == null || y <= uploadYear + 1)
        opinions.forEach { o ->
            val y = o.releaseYear
            if (y != null && !valid(y)) notes.append("ignored implausible year $y; ")
        }
        var year = opinions
            .mapNotNull { o -> o.releaseYear?.takeIf { valid(it) }?.let { it to o.yearConfidence } }
            .maxByOrNull { it.second }?.first
        if (year == null && uploadYear != null && uploadYear >= currentYear - 1) {
            // Nobody knew the song and the video is brand new: it is almost certainly a new release.
            year = uploadYear
            notes.append("year inferred from a recent upload; ")
        }
        val lead = review ?: judge
        return SongVerdict(
            moods = moods,
            era = year?.let { Eras.of(it) },
            releaseYear = year,
            summary = lead.summary,
            notes = notes.toString().trim()
        )
    }
}
