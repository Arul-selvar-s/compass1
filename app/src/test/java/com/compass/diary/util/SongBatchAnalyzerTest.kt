package com.compass.diary.util

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fast sorter (many songs per question) against a fake Gemini that answers like the real one would. */
class SongBatchAnalyzerTest {

    private class FakeGemini(val handler: (GeminiRequest) -> GeminiResult) : GeminiApi {
        val requests = mutableListOf<GeminiRequest>()
        override suspend fun generate(request: GeminiRequest): GeminiResult {
            requests += request
            return handler(request)
        }
    }

    private fun promptOf(r: GeminiRequest) = r.parts.joinToString("\n") { it.optString("text") }

    private fun evidence(title: String, upload: String = "2025-03-12") = SongEvidence(
        title = title, channel = "Big Label Music", uploadDate = upload,
        description = "Movie: Some Film | Music: Composer", keywords = listOf("tamil"), note = null,
        videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    )

    private fun items(vararg titles: String) = titles.mapIndexed { i, t -> BatchItem(100L + i, evidence(t)) }

    private fun song(n: Int, year: Int?, vararg scores: Pair<String, Int>): JSONObject {
        val s = JSONObject()
        SongTags.moods.forEach { s.put(it.id, 0) }
        scores.forEach { s.put(it.first, it.second) }
        return JSONObject().put("n", n).put("release_year", year ?: JSONObject.NULL)
            .put("year_confidence", 0.9).put("mood_scores", s)
    }

    private fun answer(vararg songs: JSONObject): String {
        val arr = JSONArray()
        songs.forEach { arr.put(it) }
        return JSONObject().put("songs", arr).toString()
    }

    private fun text(s: String) = GeminiResult.Text(s, false, emptyList())

    private fun analyzer(api: GeminiApi) = SongBatchAnalyzer(api, currentYear = { 2026 })

    @Test fun oneQuestionSortsAWholeBatch() = runBlocking {
        val api = FakeGemini {
            text(answer(
                song(1, 2025, "spicy" to 8, "item" to 7),
                song(2, 1996, "love" to 9),
                song(3, 2012, "sad" to 8)
            ))
        }
        val out = analyzer(api).analyze(items("A", "B", "C")) as BatchOutcome.Answered
        assertEquals(1, api.requests.size)
        assertEquals(listOf(SongTag.SPICY, SongTag.ITEM), out.verdicts.getValue(100L).moods)
        assertEquals(SongTag.E2020S, out.verdicts.getValue(100L).era)
        assertEquals(SongTag.E90S, out.verdicts.getValue(101L).era)
        assertEquals(listOf(SongTag.SAD), out.verdicts.getValue(102L).moods)
        assertEquals(SongTag.E2010S, out.verdicts.getValue(102L).era)
    }

    @Test fun theQuestionIsFastAndStructured() = runBlocking {
        val api = FakeGemini { text(answer(song(1, 2025, "love" to 9), song(2, 2025, "love" to 9))) }
        analyzer(api).analyze(items("First Song", "Second Song"))
        val r = api.requests.single()
        assertFalse("no web research", r.grounded)
        assertTrue("no video listening", r.parts.none { it.has("file_data") })
        assertTrue(r.json && r.schema != null)
        assertTrue("little thinking", r.thinkingBudget <= 4096)
        val prompt = promptOf(r)
        assertTrue(prompt.contains("First Song") && prompt.contains("Second Song"))
        assertTrue(prompt.contains("SONG 1") && prompt.contains("SONG 2"))
        assertTrue(prompt.contains("2025-03-12") && prompt.contains("Movie: Some Film"))
    }

    @Test fun songsTheAnswerSkippedAreAskedAgainOnce() = runBlocking {
        var call = 0
        val api = FakeGemini {
            if (call++ == 0) text(answer(song(1, 2025, "love" to 9), song(2, 2025, "sad" to 9)))
            else text(answer(song(1, 2025, "dance" to 9)))   // numbered again from 1: only the skipped song is in it
        }
        val out = analyzer(api).analyze(items("A", "B", "Skipped One")) as BatchOutcome.Answered
        assertEquals(2, api.requests.size)
        val second = promptOf(api.requests[1])
        assertTrue(second.contains("Skipped One") && !second.contains("\"A\"") && !second.contains("\"B\""))
        assertEquals(3, out.verdicts.size)
        assertEquals(listOf(SongTag.DANCE), out.verdicts.getValue(102L).moods)
    }

    @Test fun aSongNothingFitsIsLeftOutSoItCanBeCounted() = runBlocking {
        val api = FakeGemini { text(answer(song(1, 2025, "love" to 9), song(2, 2025))) }   // song 2: every mood 0
        val out = analyzer(api).analyze(items("A", "B")) as BatchOutcome.Answered
        assertEquals(setOf(100L), out.verdicts.keys)
        assertEquals(1, api.requests.size)   // it was answered, so it is not asked again right now
    }

    @Test fun unreadableAnswersFailAfterOneRetry() = runBlocking {
        val api = FakeGemini { text("sorry, I cannot do that") }
        val out = analyzer(api).analyze(items("A", "B"))
        assertTrue(out is BatchOutcome.Failed)
        assertEquals(2, api.requests.size)
    }

    @Test fun stopSignalsComeBackAtOnce() = runBlocking {
        assertEquals(BatchOutcome.Offline, analyzer(FakeGemini { GeminiResult.Offline }).analyze(items("A")))
        assertEquals(BatchOutcome.RateLimited(daily = true), analyzer(FakeGemini { GeminiResult.RateLimited(true) }).analyze(items("A")))
        assertEquals(BatchOutcome.RateLimited(daily = false), analyzer(FakeGemini { GeminiResult.RateLimited(false) }).analyze(items("A")))
        assertEquals(BatchOutcome.BadKey, analyzer(FakeGemini { GeminiResult.BadKey }).analyze(items("A")))
    }

    @Test fun aLimitInTheSecondRoundKeepsWhatTheFirstRoundSorted() = runBlocking {
        var call = 0
        val api = FakeGemini {
            if (call++ == 0) text(answer(song(1, 2025, "love" to 9))) else GeminiResult.RateLimited(false)
        }
        val out = analyzer(api).analyze(items("A", "B")) as BatchOutcome.Answered
        assertEquals(setOf(100L), out.verdicts.keys)
    }

    @Test fun aReleaseYearAfterTheUploadIsNotBelieved() = runBlocking {
        val api = FakeGemini { text(answer(song(1, 2025, "love" to 9))) }
        val old = listOf(BatchItem(1L, evidence("Old Upload", upload = "2005-01-01")))
        val v = (analyzer(api).analyze(old) as BatchOutcome.Answered).verdicts.getValue(1L)
        assertNull("a song cannot be newer than its upload", v.era)
        assertEquals(listOf(SongTag.LOVE), v.moods)
    }

    @Test fun nothingToAskMeansNoCall() = runBlocking {
        val api = FakeGemini { error("must not be called") }
        val out = analyzer(api).analyze(emptyList()) as BatchOutcome.Answered
        assertTrue(out.verdicts.isEmpty() && api.requests.isEmpty())
    }

    // ---- reading the answer ----

    @Test fun theParserAcceptsFencesAndBareArrays() {
        val one = song(1, 2020, "love" to 9).toString()
        val two = song(2, 1999, "sad" to 9).toString()
        val fenced = "```json\n{\"songs\": [$one, $two]}\n```"
        val bare = "Here you go: [$one, $two]"
        for (text in listOf(fenced, bare)) {
            val parsed = BatchParser.parse(text)
            assertEquals(setOf(1, 2), parsed.keys)
            assertEquals(2020, parsed.getValue(1).releaseYear)
            assertEquals(9f, parsed.getValue(2).scores.getValue(SongTag.SAD), 0.001f)
        }
    }

    @Test fun theParserSkipsBrokenEntriesAndGarbage() {
        val good = song(2, 2018, "happy" to 8).toString()
        val noScores = "{\"n\": 1, \"release_year\": 2000}"
        assertEquals(setOf(2), BatchParser.parse("{\"songs\": [$noScores, $good]}").keys)
        assertTrue(BatchParser.parse("not json at all").isEmpty())
        assertTrue(BatchParser.parse("").isEmpty())
    }
}
