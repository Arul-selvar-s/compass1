package com.compass.diary.util

import org.json.JSONArray
import org.json.JSONObject

enum class SongTagGroup { MOOD, ERA }

/**
 * Every sub-category a song can be sorted into. [id] is what is stored in the database and in the
 * Drive backup, so never change an id once released (labels can change freely).
 */
enum class SongTag(val id: String, val label: String, val group: SongTagGroup, val meaning: String) {
    MELODY("melody", "Melody", SongTagGroup.MOOD, "soft, soothing, tune-led songs you can relax to"),
    LOVE("love", "Love", SongTagGroup.MOOD, "romantic songs about being in love, longing, proposing, a couple's feelings"),
    SAD("sad", "Sad / Breakup", SongTagGroup.MOOD, "heartbreak, separation, pain, loss, loneliness"),
    SPICY("spicy", "Spicy / Mass", SongTagGroup.MOOD, "sensual, steamy, flirty or glamorous songs, and high-voltage mass hero-style numbers"),
    ITEM("item", "Item songs", SongTagGroup.MOOD, "special item-number / club-style dance numbers"),
    DANCE("dance", "Dance / Kuthu", SongTagGroup.MOOD, "upbeat dance, kuthu and folk-beat songs made to dance to"),
    HAPPY("happy", "Happy", SongTagGroup.MOOD, "joyful, feel-good, celebration songs"),
    MOTIVATIONAL("motivational", "Motivational", SongTagGroup.MOOD, "inspiring songs about confidence, struggle, rising up, life lessons"),
    DEVOTIONAL("devotional", "Devotional", SongTagGroup.MOOD, "god, prayer and spiritual songs"),
    FRIENDSHIP("friendship", "Friendship", SongTagGroup.MOOD, "songs about friends, bonding, college and school days"),

    CLASSIC("classic", "Classic (70s & older)", SongTagGroup.ERA, "released in 1979 or earlier"),
    E80S("80s", "80s", SongTagGroup.ERA, "released 1980-1989"),
    E90S("90s", "90s", SongTagGroup.ERA, "released 1990-1999"),
    E2000S("2000s", "2000s", SongTagGroup.ERA, "released 2000-2009"),
    E2010S("2010s", "2010s", SongTagGroup.ERA, "released 2010-2019"),
    E2020S("2020s", "2020s", SongTagGroup.ERA, "released 2020 or later");
}

object SongTags {
    val moods: List<SongTag> = SongTag.values().filter { it.group == SongTagGroup.MOOD }
    val eras: List<SongTag> = SongTag.values().filter { it.group == SongTagGroup.ERA }

    /** Accepts an id ("90s"), a label ("Sad / Breakup") or a loose spelling ("90's", "Love"). */
    fun fromText(raw: String?): SongTag? {
        val t = raw?.trim()?.lowercase()?.replace("’", "")?.replace("'", "") ?: return null
        if (t.isEmpty()) return null
        return SongTag.values().firstOrNull { it.id == t || it.label.lowercase() == t }
            ?: when (t) {
                "1980s", "eighties" -> SongTag.E80S
                "1990s", "nineties" -> SongTag.E90S
                "2000", "noughties", "00s" -> SongTag.E2000S
                "2010", "10s" -> SongTag.E2010S
                "2020", "20s" -> SongTag.E2020S
                "sad/breakup", "breakup" -> SongTag.SAD
                "spicy/mass", "mass" -> SongTag.SPICY
                "item song", "item songs", "item number" -> SongTag.ITEM
                "dance/kuthu", "kuthu" -> SongTag.DANCE
                "romantic", "romance" -> SongTag.LOVE
                else -> null
            }
    }

    fun parse(stored: String?): Set<SongTag> =
        stored.orEmpty().split(',').mapNotNull { fromText(it) }.toSet()

    /** Stable order (declaration order) so the same tags always serialize identically. */
    fun serialize(tags: Collection<SongTag>): String =
        SongTag.values().filter { it in tags }.joinToString(",") { it.id }

    fun has(stored: String?, tag: SongTag): Boolean = tag in parse(stored)
}

/** What the model decided about one song. */
data class SongClassification(
    val identified: Boolean,
    val confidence: Float,
    val summary: String,
    val moods: List<SongTag>,
    val era: SongTag?
) {
    val tags: List<SongTag> get() = moods + listOfNotNull(era)

    /** Sure enough that no second, deeper look (the video itself) is needed. */
    val isConfident: Boolean get() = identified && confidence >= 0.6f && moods.isNotEmpty()
}

object SongPrompt {
    private fun tagList(group: SongTagGroup) =
        SongTag.values().filter { it.group == group }.joinToString("\n") { "- ${it.id}: ${it.meaning}" }

    fun build(title: String?, channel: String?, note: String?, withVideo: Boolean): String = buildString {
        appendLine("You are an expert in Indian film music and pop songs (Tamil, Telugu, Malayalam, Kannada, Hindi) as well as international songs.")
        appendLine()
        if (withVideo) {
            appendLine("The attached YouTube video is a song. Listen to it: understand the lyrics, what the song is saying, its mood, tempo and style.")
        }
        if (!title.isNullOrBlank()) appendLine("YouTube title: \"$title\"")
        if (!channel.isNullOrBlank()) appendLine("YouTube channel: \"$channel\"")
        if (!note.isNullOrBlank()) appendLine("Note from the person who shared it: \"$note\"")
        appendLine()
        appendLine("Decide how to sort this song by its MEANING and sound - not by words that happen to appear in its title:")
        appendLine("1. Work out which song this really is (film, singer, music director, release year) and recall what its lyrics are about.")
        appendLine("2. If you do not genuinely know this song, say identified=false with a low confidence instead of guessing from the title.")
        appendLine("3. moods: choose 1 to 3 tags that truly fit the song (ids only):")
        appendLine(tagList(SongTagGroup.MOOD))
        appendLine("4. era: choose exactly one, by the ORIGINAL release year of the song (not the year the video was uploaded), or \"unknown\":")
        appendLine(tagList(SongTagGroup.ERA))
        appendLine("5. summary: one short sentence saying what the song is about.")
        appendLine()
        append("Reply with JSON only: {\"identified\": true|false, \"confidence\": 0.0-1.0, \"summary\": \"...\", \"moods\": [ids], \"era\": \"id or unknown\"}")
    }

    /** Gemini structured-output schema (keeps answers machine-readable). */
    fun schema(): JSONObject {
        val moodIds = JSONArray().also { a -> SongTags.moods.forEach { a.put(it.id) } }
        val eraIds = JSONArray().also { a -> SongTags.eras.forEach { a.put(it.id) }; a.put("unknown") }
        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("identified", JSONObject().put("type", "BOOLEAN"))
                .put("confidence", JSONObject().put("type", "NUMBER"))
                .put("summary", JSONObject().put("type", "STRING"))
                .put("moods", JSONObject().put("type", "ARRAY")
                    .put("items", JSONObject().put("type", "STRING").put("format", "enum").put("enum", moodIds)))
                .put("era", JSONObject().put("type", "STRING").put("format", "enum").put("enum", eraIds)))
            .put("required", JSONArray().put("identified").put("confidence").put("moods").put("era"))
    }
}

object SongClassificationParser {

    /** The text the model produced -> a validated classification (unknown tags are dropped). */
    fun parseModelText(text: String): SongClassification? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(text.substring(start, end + 1))
            val moods = LinkedHashSet<SongTag>()
            val arr = o.optJSONArray("moods") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val tag = SongTags.fromText(arr.optString(i))
                if (tag != null && tag.group == SongTagGroup.MOOD) moods.add(tag)
            }
            val era = SongTags.fromText(o.optString("era", ""))?.takeIf { it.group == SongTagGroup.ERA }
            SongClassification(
                identified = o.optBoolean("identified", true),
                confidence = o.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                summary = o.optString("summary", ""),
                moods = moods.take(3),
                era = era
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Whole generateContent HTTP response -> the model's text (null if blocked/empty). */
    fun textOfResponse(body: String): String? = try {
        val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null) null else (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf { s -> s.isNotBlank() } }
            .joinToString("")
            .ifBlank { null }
    } catch (e: Exception) {
        null
    }

    fun parseApiResponse(body: String): SongClassification? = textOfResponse(body)?.let { parseModelText(it) }

    /** "retryDelay":"23s" inside a 429 error -> 23000 (clamped to something sensible). */
    fun retryDelayMs(errorBody: String): Long {
        val fallback = 20_000L
        return try {
            val details = JSONObject(errorBody).optJSONObject("error")?.optJSONArray("details") ?: return fallback
            for (i in 0 until details.length()) {
                val d = details.optJSONObject(i)?.optString("retryDelay", "") ?: ""
                val secs = d.removeSuffix("s").toDoubleOrNull()
                if (secs != null) return (secs * 1000).toLong().coerceIn(10_000L, 90_000L)
            }
            fallback
        } catch (e: Exception) {
            fallback
        }
    }
}

object YoutubeIds {
    private val patterns = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})""")
    )

    fun extract(url: String): String? = patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    fun canonicalUrl(url: String): String? = extract(url)?.let { "https://www.youtube.com/watch?v=$it" }
}
