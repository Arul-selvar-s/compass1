package com.compass.diary.util

import com.compass.diary.data.local.database.MIGRATION_7_8_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class SongClassificationTest {

    // ---- tags ----

    @Test fun tagTextIsMatchedLoosely() {
        assertEquals(SongTag.E90S, SongTags.fromText("90's"))
        assertEquals(SongTag.E90S, SongTags.fromText("90s"))
        assertEquals(SongTag.E90S, SongTags.fromText("1990s"))
        assertEquals(SongTag.LOVE, SongTags.fromText(" Love "))
        assertEquals(SongTag.SAD, SongTags.fromText("Sad / Breakup"))
        assertEquals(SongTag.ITEM, SongTags.fromText("item songs"))
        assertNull(SongTags.fromText("unknown"))
        assertNull(SongTags.fromText(""))
        assertNull(SongTags.fromText(null))
    }

    @Test fun everyTagRoundTripsThroughItsId() {
        SongTag.values().forEach { assertEquals(it, SongTags.fromText(it.id)) }
        assertEquals(SongTag.values().size, SongTag.values().map { it.id }.toSet().size)
    }

    @Test fun storedTagsRoundTripInStableOrder() {
        val stored = SongTags.serialize(setOf(SongTag.E90S, SongTag.LOVE, SongTag.MELODY))
        assertEquals("melody,love,90s", stored)
        assertEquals(setOf(SongTag.MELODY, SongTag.LOVE, SongTag.E90S), SongTags.parse(stored))
        assertTrue(SongTags.parse(null).isEmpty())
        assertTrue(SongTags.parse("").isEmpty())
        assertEquals(setOf(SongTag.LOVE), SongTags.parse("love,not-a-tag"))
    }

    @Test fun aSongWithSeveralTagsIsFoundUnderEachOfThem() {
        val stored = "love,90s"
        assertTrue(SongTags.has(stored, SongTag.LOVE))
        assertTrue(SongTags.has(stored, SongTag.E90S))
        assertFalse(SongTags.has(stored, SongTag.SAD))
        assertFalse(SongTags.has(null, SongTag.LOVE))   // not sorted yet -> only in "All"
    }

    // ---- model answer parsing ----

    @Test fun parsesAConfidentAnswer() {
        val c = SongClassificationParser.parseModelText(
            """{"identified":true,"confidence":0.92,"summary":"A man pines for his beloved","moods":["love","melody"],"era":"90s"}"""
        )!!
        assertEquals(listOf(SongTag.LOVE, SongTag.MELODY), c.moods)
        assertEquals(SongTag.E90S, c.era)
        assertEquals(listOf(SongTag.LOVE, SongTag.MELODY, SongTag.E90S), c.tags)
        assertTrue(c.isConfident)
    }

    @Test fun dropsInvalidTagsAndCapsMoodsAtThree() {
        val c = SongClassificationParser.parseModelText(
            """{"identified":true,"confidence":0.9,"moods":["love","90s","sad","happy","melody","nonsense"],"era":"love"}"""
        )!!
        assertEquals(listOf(SongTag.LOVE, SongTag.SAD, SongTag.HAPPY), c.moods)   // 90s is an era, nonsense is unknown
        assertNull(c.era)                                                          // "love" is not an era
    }

    @Test fun unknownEraAndLowConfidenceAreNotConfident() {
        val c = SongClassificationParser.parseModelText(
            """{"identified":false,"confidence":0.2,"moods":["melody"],"era":"unknown"}"""
        )!!
        assertNull(c.era)
        assertFalse(c.isConfident)
        assertFalse(SongClassificationParser.parseModelText("""{"identified":true,"confidence":0.59,"moods":["love"],"era":"90s"}""")!!.isConfident)
        assertFalse(SongClassificationParser.parseModelText("""{"identified":true,"confidence":0.9,"moods":[],"era":"90s"}""")!!.isConfident)
    }

    @Test fun toleratesCodeFencesAndGarbage() {
        val fenced = "```json\n{\"identified\":true,\"confidence\":1,\"moods\":[\"devotional\"],\"era\":\"2000s\"}\n```"
        assertEquals(listOf(SongTag.DEVOTIONAL), SongClassificationParser.parseModelText(fenced)!!.moods)
        assertNull(SongClassificationParser.parseModelText("I could not decide"))
        assertNull(SongClassificationParser.parseModelText("{broken json"))
    }

    @Test fun readsTheTextOutOfAGeminiEnvelope() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"{\"identified\":true,\"confidence\":0.8,\"moods\":[\"spicy\"],\"era\":\"2010s\"}"}]}}]}"""
        val c = SongClassificationParser.parseApiResponse(body)!!
        assertEquals(listOf(SongTag.SPICY), c.moods)
        assertEquals(SongTag.E2010S, c.era)
        assertNull(SongClassificationParser.parseApiResponse("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        assertNull(SongClassificationParser.parseApiResponse("not json"))
    }

    @Test fun readsGeminisRetryDelay() {
        val body = """{"error":{"code":429,"details":[{"@type":"x.RetryInfo","retryDelay":"23s"}]}}"""
        assertEquals(23_000L, SongClassificationParser.retryDelayMs(body))
        assertEquals(90_000L, SongClassificationParser.retryDelayMs("""{"error":{"details":[{"retryDelay":"600s"}]}}"""))
        assertEquals(20_000L, SongClassificationParser.retryDelayMs("nope"))
    }

    // ---- prompt ----

    @Test fun promptAsksForMeaningAndListsEveryTag() {
        val p = SongPrompt.build("Vennilave Vennilave", "Sony Music South", "our song", withVideo = false)
        assertTrue(p.contains("Vennilave Vennilave"))
        assertTrue(p.contains("our song"))
        assertTrue(p.contains("MEANING"))
        SongTag.values().forEach { assertTrue("prompt must list ${it.id}", p.contains("- ${it.id}:")) }
        assertFalse(p.contains("Listen to it"))
        assertTrue(SongPrompt.build(null, null, null, withVideo = true).contains("Listen to it"))
    }

    @Test fun schemaOffersExactlyTheKnownTags() {
        val props = SongPrompt.schema().getJSONObject("properties")
        val moods = props.getJSONObject("moods").getJSONObject("items").getJSONArray("enum")
        val eras = props.getJSONObject("era").getJSONArray("enum")
        assertEquals(SongTags.moods.map { it.id }, (0 until moods.length()).map { moods.getString(it) })
        assertEquals(SongTags.eras.map { it.id } + "unknown", (0 until eras.length()).map { eras.getString(it) })
    }

    // ---- YouTube ids ----

    @Test fun extractsVideoIds() {
        assertEquals("dQw4w9WgXcQ", YoutubeIds.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s"))
        assertEquals("dQw4w9WgXcQ", YoutubeIds.extract("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", YoutubeIds.extract("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YoutubeIds.canonicalUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(YoutubeIds.extract("https://example.com/not-youtube"))
    }

    // ---- database migration (real SQLite) ----

    @Test fun migrationKeepsExistingSongsAndAddsTagColumns() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                // the v7 table exactly as Room created it
                st.execute("CREATE TABLE song_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, youtubeUrl TEXT NOT NULL, note TEXT, sender TEXT NOT NULL, sentAt INTEGER NOT NULL, title TEXT)")
                st.execute("INSERT INTO song_messages (youtubeUrl, note, sender, sentAt, title) VALUES ('https://youtu.be/dQw4w9WgXcQ', 'hi', 'JENMASANI', 1000, 'Song')")
                MIGRATION_7_8_SQL.forEach { st.execute(it) }
                st.executeQuery("SELECT youtubeUrl, note, sender, sentAt, title, categories, categoriesAt, categoryTries FROM song_messages").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("https://youtu.be/dQw4w9WgXcQ", rs.getString("youtubeUrl"))
                    assertEquals("hi", rs.getString("note"))
                    assertEquals("JENMASANI", rs.getString("sender"))
                    assertEquals(1000L, rs.getLong("sentAt"))
                    assertEquals("Song", rs.getString("title"))
                    assertNull(rs.getString("categories"))      // unsorted -> the AI will pick it up
                    assertEquals(0L, rs.getLong("categoriesAt"))
                    assertEquals(0, rs.getInt("categoryTries"))
                    assertFalse(rs.next())
                }
            }
        }
    }
}
