package com.compass.diary.util

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole deep analysis against a fake Gemini that answers like the real one would. */
class SongAnalyzerTest {

    private class FakeGemini(val handler: (GeminiRequest) -> GeminiResult) : GeminiApi {
        val requests = mutableListOf<GeminiRequest>()
        override suspend fun generate(request: GeminiRequest): GeminiResult {
            requests += request
            return handler(request)
        }
    }

    private fun promptOf(r: GeminiRequest) = r.parts.joinToString("\n") { it.optString("text") }
    private val evidence = SongEvidence(
        title = "New Hit Song", channel = "Big Label Music", uploadDate = "2025-03-12",
        description = "Movie: Some Film (2025) | Music: Composer", keywords = listOf("tamil"), note = null,
        videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    )

    private fun judgeJson(year: Int?, vararg scores: Pair<String, Int>): String {
        val s = JSONObject()
        SongTags.moods.forEach { s.put(it.id, 0) }
        scores.forEach { s.put(it.first, it.second) }
        return JSONObject()
            .put("release_year", year ?: JSONObject.NULL).put("year_evidence", "research notes")
            .put("year_confidence", 0.9).put("lyrics_summary", "a bold dance number")
            .put("mood_scores", s).put("reasoning", "r").toString()
    }

    private fun analyzer(api: GeminiApi) = SongAnalyzer(api, pace = {}, currentYear = { 2026 })

    /** A cooperative Gemini: research -> text, video -> text, json -> judge then reviewer. */
    private fun cooperative(judge: String, review: String = judge): FakeGemini {
        var jsonCalls = 0
        return FakeGemini { r ->
            when {
                r.grounded -> GeminiResult.Text("Released 14 February 2025 (Wikipedia). A glamorous dance number.", true, listOf("Wikipedia"))
                r.parts.any { it.has("file_data") } -> GeminiResult.Text("Fast beat, bold lyrics, glamorous dancing.", false, emptyList())
                else -> GeminiResult.Text(if (jsonCalls++ == 0) judge else review, false, emptyList())
            }
        }
    }

    @Test fun aNewSpicySongIsSortedAsSpicyAnd2020sNotThe90s() = runBlocking {
        val api = cooperative(judgeJson(2025, "spicy" to 8, "item" to 7, "dance" to 7, "melody" to 1))
        val result = analyzer(api).analyze(evidence) as Analysis.Done
        assertEquals(listOf(SongTag.SPICY, SongTag.ITEM, SongTag.DANCE), result.verdict.moods)
        assertEquals(SongTag.E2020S, result.verdict.era)
        assertEquals(4, api.requests.size)   // research, listen, judge, review
    }

    @Test fun everyStepSeesTheEvidenceFromTheEarlierSteps() = runBlocking {
        val api = cooperative(judgeJson(2025, "spicy" to 8))
        analyzer(api).analyze(evidence)
        val research = api.requests[0]
        assertTrue("research uses Google Search", research.grounded && !research.json)
        assertTrue(promptOf(research).contains("2025-03-12") && promptOf(research).contains("Movie: Some Film"))
        val listen = api.requests[1]
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", listen.parts[0].getJSONObject("file_data").getString("file_uri"))
        assertTrue(listen.lowRes)
        val judge = promptOf(api.requests[2])
        assertTrue(judge.contains("Released 14 February 2025") && judge.contains("glamorous dancing") && judge.contains("Wikipedia"))
        assertTrue(api.requests[2].json && api.requests[2].schema != null)
        val review = promptOf(api.requests[3])
        assertTrue("reviewer sees the judge's answer", review.contains("year_evidence") && review.contains("research notes"))
    }

    @Test fun theReviewerCanFixTheJudge() = runBlocking {
        val api = cooperative(
            judge = judgeJson(1996, "love" to 8),
            review = judgeJson(2025, "love" to 8, "spicy" to 7)
        )
        val v = (analyzer(api).analyze(evidence) as Analysis.Done).verdict
        assertEquals(2025, v.releaseYear)
        assertEquals(SongTag.E2020S, v.era)
        assertTrue(SongTag.LOVE in v.moods)
    }

    @Test fun stillWorksWhenResearchAndListeningFail() = runBlocking {
        var json = 0
        val api = FakeGemini { r ->
            when {
                r.grounded || r.parts.any { it.has("file_data") } -> GeminiResult.Failed("no luck")
                else -> GeminiResult.Text(judgeJson(2025, "melody" to 9).also { json++ }, false, emptyList())
            }
        }
        val done = analyzer(api).analyze(evidence) as Analysis.Done
        assertEquals(listOf(SongTag.MELODY), done.verdict.moods)
        assertTrue(promptOf(api.requests[2]).contains("web search was NOT available"))
    }

    @Test fun aBrokenReviewFallsBackToTheJudge() = runBlocking {
        var json = 0
        val api = FakeGemini { r ->
            when {
                r.grounded -> GeminiResult.Text("research", true, emptyList())
                r.parts.any { it.has("file_data") } -> GeminiResult.Text("listening", false, emptyList())
                json++ == 0 -> GeminiResult.Text(judgeJson(2025, "love" to 9), false, emptyList())
                else -> GeminiResult.Text("sorry, I cannot do that", false, emptyList())
            }
        }
        val done = analyzer(api).analyze(evidence) as Analysis.Done
        assertEquals(listOf(SongTag.LOVE), done.verdict.moods)
    }

    @Test fun aGarbledJudgeAnswerIsAskedAgainOnceThenFails() = runBlocking {
        val api = FakeGemini { r ->
            when {
                r.grounded -> GeminiResult.Text("research", true, emptyList())
                r.parts.any { it.has("file_data") } -> GeminiResult.Text("listening", false, emptyList())
                else -> GeminiResult.Text("not json", false, emptyList())
            }
        }
        val result = analyzer(api).analyze(evidence)
        assertTrue(result is Analysis.Failed)
        assertEquals(4, api.requests.size)   // research, listen, judge x2
    }

    @Test fun stopsAtOnceWhenOfflineLimitedOrKeyIsBad() = runBlocking {
        assertEquals(Analysis.Offline, analyzer(FakeGemini { GeminiResult.Offline }).analyze(evidence))
        assertEquals(Analysis.RateLimited(daily = true), analyzer(FakeGemini { GeminiResult.RateLimited(true) }).analyze(evidence))
        assertEquals(Analysis.BadKey, analyzer(FakeGemini { GeminiResult.BadKey }).analyze(evidence))
        // a limit hit half way (while listening) also stops, without wasting the later calls
        val api = FakeGemini { r -> if (r.grounded) GeminiResult.Text("ok", true, emptyList()) else GeminiResult.RateLimited(false) }
        assertEquals(Analysis.RateLimited(daily = false), analyzer(api).analyze(evidence))
        assertEquals(2, api.requests.size)
    }

    @Test fun skipsListeningWhenThereIsNoVideoUrl() = runBlocking {
        val api = cooperative(judgeJson(2025, "love" to 9))
        analyzer(api).analyze(evidence.copy(videoUrl = null))
        assertTrue(api.requests.none { r -> r.parts.any { it.has("file_data") } })
        assertEquals(3, api.requests.size)
    }
}
