package com.compass.diary.util

import com.compass.diary.data.local.database.MIGRATION_7_8_SQL
import com.compass.diary.data.local.database.MIGRATION_8_9_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class SongSortingTest {

    // ---- tags ----

    @Test fun tagTextIsMatchedLoosely() {
        assertEquals(SongTag.E90S, SongTags.fromText("90's"))
        assertEquals(SongTag.E90S, SongTags.fromText("1990s"))
        assertEquals(SongTag.LOVE, SongTags.fromText(" Love "))
        assertEquals(SongTag.SAD, SongTags.fromText("Sad / Breakup"))
        assertEquals(SongTag.SPICY, SongTags.fromText("spicy"))
        assertNull(SongTags.fromText("unknown"))
        assertNull(SongTags.fromText(null))
    }

    @Test fun storedTagsRoundTripInStableOrder() {
        val stored = SongTags.serialize(setOf(SongTag.E2020S, SongTag.SPICY, SongTag.ITEM))
        assertEquals("spicy,item,2020s", stored)
        assertEquals(setOf(SongTag.SPICY, SongTag.ITEM, SongTag.E2020S), SongTags.parse(stored))
        assertTrue(SongTags.parse(null).isEmpty())
        assertEquals(setOf(SongTag.LOVE), SongTags.parse("love,not-a-tag"))
    }

    @Test fun aSongWithSeveralTagsIsFoundUnderEachOfThem() {
        assertTrue(SongTags.has("love,90s", SongTag.LOVE))
        assertTrue(SongTags.has("love,90s", SongTag.E90S))
        assertFalse(SongTags.has("love,90s", SongTag.SAD))
        assertFalse(SongTags.has(null, SongTag.LOVE))
    }

    // ---- eras come from the release year ----

    @Test fun erasAreDerivedFromTheYear() {
        assertEquals(SongTag.CLASSIC, Eras.of(1975))
        assertEquals(SongTag.E80S, Eras.of(1980))
        assertEquals(SongTag.E90S, Eras.of(1999))
        assertEquals(SongTag.E2000S, Eras.of(2000))
        assertEquals(SongTag.E2010S, Eras.of(2019))
        assertEquals(SongTag.E2020S, Eras.of(2020))
        assertEquals(SongTag.E2020S, Eras.of(2025))   // the bug: a 2025 song must never land in the 90s
    }

    // ---- scoring + verdict ----

    private fun judged(year: Int?, conf: Float, vararg scores: Pair<SongTag, Int>) = JudgedSong(
        releaseYear = year, yearEvidence = "test", yearConfidence = conf, summary = "s",
        scores = SongTags.moods.associateWith { 0f } + scores.associate { it.first to it.second.toFloat() }, reasoning = ""
    )

    @Test fun spicyIsPickedWhenItScoresHigh() {
        val j = judged(2025, 0.9f, SongTag.SPICY to 8, SongTag.ITEM to 7, SongTag.DANCE to 7, SongTag.MELODY to 1)
        val v = SongVerdictBuilder.build(j, j, 2025, 2026)!!
        assertEquals(listOf(SongTag.SPICY, SongTag.ITEM, SongTag.DANCE), v.moods)
        assertEquals(SongTag.E2020S, v.era)
        assertEquals(listOf(SongTag.SPICY, SongTag.ITEM, SongTag.DANCE, SongTag.E2020S), v.tags)
    }

    @Test fun reviewerCorrectsTheJudgesWrongDecade() {
        // The judge said 1996 (the "everything is 90s" mistake); the reviewer found the 2025 release with evidence.
        val judge = judged(1996, 0.4f, SongTag.LOVE to 8)
        val review = judged(2025, 0.95f, SongTag.LOVE to 8)
        val v = SongVerdictBuilder.build(judge, review, 2025, 2026)!!
        assertEquals(2025, v.releaseYear)
        assertEquals(SongTag.E2020S, v.era)
    }

    @Test fun moodsAreAveragedSoOneSlipDoesNotDecide() {
        val judge = judged(2010, 0.9f, SongTag.SAD to 10, SongTag.LOVE to 6)
        val review = judged(2010, 0.9f, SongTag.SAD to 2, SongTag.LOVE to 8)
        val v = SongVerdictBuilder.build(judge, review, 2012, 2026)!!
        // love = (6+8)/2 = 7, sad = (10+2)/2 = 6 (on the threshold): the slip no longer dominates
        assertEquals(listOf(SongTag.LOVE, SongTag.SAD), v.moods)
    }

    @Test fun aYearAfterTheUploadIsImpossibleAndIgnored() {
        val j = judged(2025, 0.9f, SongTag.MELODY to 9)
        val v = SongVerdictBuilder.build(j, null, 2003, 2026)!!   // uploaded in 2003, cannot be released in 2025
        assertNull(v.releaseYear)
        assertNull(v.era)
        assertTrue(v.notes.contains("implausible"))
    }

    @Test fun anOldSongReuploadedLaterKeepsItsRealYear() {
        val j = judged(1994, 0.9f, SongTag.LOVE to 9, SongTag.MELODY to 8)
        val v = SongVerdictBuilder.build(j, j, 2019, 2026)!!   // a label re-uploaded it in 2019
        assertEquals(SongTag.E90S, v.era)
    }

    @Test fun unknownYearOfABrandNewUploadIsTreatedAsNewButOldUploadsStayUnknown() {
        val j = judged(null, 0.0f, SongTag.DANCE to 9)
        assertEquals(SongTag.E2020S, SongVerdictBuilder.build(j, null, 2026, 2026)!!.era)
        assertNull(SongVerdictBuilder.build(j, null, 2015, 2026)!!.era)   // never guess for an old upload
    }

    @Test fun nothingScoringHighEnoughMeansNoVerdict() {
        assertNull(SongVerdictBuilder.build(judged(2000, 0.9f, SongTag.LOVE to 3), null, 2001, 2026))
        val weak = SongVerdictBuilder.build(judged(2000, 0.9f, SongTag.LOVE to 5), null, 2001, 2026)!!
        assertEquals(listOf(SongTag.LOVE), weak.moods)   // best mood kept when at least 4.5
    }

    @Test fun atMostFourMoods() {
        val j = judged(2000, 0.9f, *SongTags.moods.take(7).map { it to 9 }.toTypedArray())
        assertEquals(4, SongVerdictBuilder.build(j, j, 2001, 2026)!!.moods.size)
    }

    // ---- reading the model's JSON ----

    @Test fun parsesAJudgement() {
        val text = """{"release_year":2025,"year_evidence":"Wikipedia","year_confidence":0.9,"lyrics_summary":"x",
            "mood_scores":{"melody":2,"love":3,"sad":0,"spicy":9,"item":8,"dance":7,"happy":4,"motivational":0,"devotional":0,"friendship":0},"reasoning":"r"}"""
        val j = JudgeParser.parse(text)!!
        assertEquals(2025, j.releaseYear)
        assertEquals(9f, j.scores[SongTag.SPICY]!!, 0.01f)
        assertEquals(0.9f, j.yearConfidence, 0.01f)
    }

    @Test fun parserToleratesFencesNullYearsAndMissingMoods() {
        val j = JudgeParser.parse("```json\n{\"release_year\":null,\"year_confidence\":0.1,\"mood_scores\":{\"love\":7}}\n```")!!
        assertNull(j.releaseYear)
        assertEquals(7f, j.scores[SongTag.LOVE]!!, 0.01f)
        assertEquals(0f, j.scores[SongTag.SPICY]!!, 0.01f)      // missing mood = 0
        assertEquals(1999, JudgeParser.parse("""{"release_year":"released in 1999","mood_scores":{"love":7}}""")!!.releaseYear)
        assertNull(JudgeParser.parse("no json here"))
        assertNull(JudgeParser.parse("""{"release_year":2000}"""))      // no scores at all
    }

    // ---- Gemini plumbing ----

    @Test fun readsTextSourcesAndErrors() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"hello "},{"text":"world"},{"thought":true,"text":"hidden"}]},
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"u","title":"Wikipedia"}},{"web":{"uri":"v","title":"JioSaavn"}}]}}]}"""
        assertEquals("hello world", GeminiJson.textOf(body))
        assertEquals(listOf("Wikipedia", "JioSaavn"), GeminiJson.sourcesOf(body))
        assertNull(GeminiJson.textOf("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        assertEquals("bad key", GeminiJson.errorMessage("""{"error":{"message":"bad key"}}"""))
        assertEquals(23_000L, GeminiJson.retryDelayMs("""{"error":{"details":[{"retryDelay":"23s"}]}}"""))
        assertEquals(20_000L, GeminiJson.retryDelayMs("nope"))
        assertTrue(GeminiJson.isDailyQuota("Quota exceeded for metric ... GenerateRequestsPerDayPerProjectPerModel"))
        assertFalse(GeminiJson.isDailyQuota("""{"error":{"message":"Resource exhausted per minute"}}"""))
    }

    // ---- prompts ----

    @Test fun promptsCarryTheEvidenceAndEveryMood() {
        val e = SongEvidence("Some Song", "Some Label", "2025-03-12", "Movie: X | Music: Y", listOf("tamil", "2025"), "our song", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val judge = SongPrompts.judge(e, "RESEARCH-TEXT", "LISTEN-TEXT", webSearch = true)
        listOf("Some Song", "Some Label", "2025-03-12", "Movie: X", "our song", "RESEARCH-TEXT", "LISTEN-TEXT", "NEVER use the YouTube upload date").forEach {
            assertTrue("judge prompt must contain: $it", judge.contains(it))
        }
        SongTags.moods.forEach { assertTrue("rubric lists ${it.id}", judge.contains("- ${it.id}:")) }
        assertTrue(SongPrompts.judge(e, null, null, webSearch = false).contains("web search was NOT available"))
        val review = SongPrompts.review(e, "R", "L", true, """{"release_year":1996}""")
        assertTrue(review.contains("""{"release_year":1996}"""))
        assertTrue(review.contains("A new song must not be placed in an older decade"))
        assertTrue(review.contains("Spicy / mass"))
        assertTrue(SongPrompts.research(e).contains("OFFICIAL RELEASE DATE"))
    }

    @Test fun schemaScoresEveryMood() {
        val props = SongPrompts.judgeSchema().getJSONObject("properties").getJSONObject("mood_scores").getJSONObject("properties")
        assertEquals(SongTags.moods.map { it.id }.toSet(), props.keys().asSequence().toSet())
    }

    // ---- YouTube ----

    @Test fun extractsVideoIds() {
        assertEquals("dQw4w9WgXcQ", YoutubeIds.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s"))
        assertEquals("dQw4w9WgXcQ", YoutubeIds.extract("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YoutubeIds.canonicalUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(YoutubeIds.extract("https://example.com/x"))
    }

    @Test fun readsTheWatchPage() {
        val html = """<script>var x = {"videoDetails":{"keywords":["tamil song","2025 hits","a \"quoted\" tag"],
            "shortDescription":"Movie: Test\nMusic: Someone\n\"Official\" lyric video","lengthSeconds":"251"},
            "microformat":{"publishDate":"2025-03-12T05:30:07-07:00","uploadDate":"2025-03-12T05:30:07-07:00"}}</script>"""
        val page = YoutubePageParser.parse(html)
        assertEquals("2025-03-12", page.uploadDate)
        assertEquals("Movie: Test\nMusic: Someone\n\"Official\" lyric video", page.description)
        assertEquals(listOf("tamil song", "2025 hits", "a \"quoted\" tag"), page.keywords)
        val empty = YoutubePageParser.parse("<html></html>")
        assertNull(empty.uploadDate); assertNull(empty.description); assertTrue(empty.keywords.isEmpty())
    }

    // ---- ETA + policy ----

    @Test fun formatsTimeLeft() {
        assertNull(EtaFormat.text(null))
        assertEquals("under a minute", EtaFormat.text(30_000))
        assertEquals("about 25 min", EtaFormat.text(25 * 60_000L))
        assertEquals("about 2 h", EtaFormat.text(120 * 60_000L))
        assertEquals("about 2 h 10 min", EtaFormat.text(130 * 60_000L))
    }

    @Test fun sortingPolicy() {
        assertTrue(SongSortPolicy.needsSorting(SongSortPolicy.TIER_QUICK, 0))     // new song or old quick result
        assertFalse(SongSortPolicy.needsSorting(SongSortPolicy.TIER_DEEP, 0))
        assertFalse(SongSortPolicy.needsSorting(SongSortPolicy.TIER_MANUAL, 0))   // hand edits are never overwritten
        assertFalse(SongSortPolicy.needsSorting(SongSortPolicy.TIER_QUICK, SongSortPolicy.MAX_TRIES))
    }

    // ---- database migrations (real SQLite) ----

    @Test fun migrationsKeepExistingSongsAndAddTheColumns() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE song_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, youtubeUrl TEXT NOT NULL, note TEXT, sender TEXT NOT NULL, sentAt INTEGER NOT NULL, title TEXT)")
                st.execute("INSERT INTO song_messages (youtubeUrl, note, sender, sentAt, title) VALUES ('https://youtu.be/dQw4w9WgXcQ', 'hi', 'JENMASANI', 1000, 'Song')")
                (MIGRATION_7_8_SQL + MIGRATION_8_9_SQL).forEach { st.execute(it) }
                st.executeQuery("SELECT youtubeUrl, note, sender, sentAt, title, categories, categoriesAt, categoryTries, categoryTier FROM song_messages").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("hi", rs.getString("note"))
                    assertEquals("Song", rs.getString("title"))
                    assertNull(rs.getString("categories"))
                    assertEquals(0L, rs.getLong("categoriesAt"))
                    assertEquals(0, rs.getInt("categoryTries"))
                    assertEquals(0, rs.getInt("categoryTier"))     // quick-tier: will be re-sorted deeply
                    assertFalse(rs.next())
                }
            }
        }
    }
}
