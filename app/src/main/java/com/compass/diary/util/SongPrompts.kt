package com.compass.diary.util

import org.json.JSONArray
import org.json.JSONObject

object SongPrompts {

    private fun moodList() = SongTags.moods.joinToString("\n") { "- ${it.id}: ${it.meaning}" }

    private fun facts(e: SongEvidence): String = buildString {
        appendLine("Facts about the YouTube video:")
        if (!e.title.isNullOrBlank()) appendLine("- Title: \"${e.title}\"")
        if (!e.channel.isNullOrBlank()) appendLine("- Channel: \"${e.channel}\"")
        if (!e.uploadDate.isNullOrBlank()) {
            appendLine("- Uploaded to YouTube on: ${e.uploadDate}  (an UPLOAD date is not the release date - labels often re-upload old songs)")
        }
        if (!e.description.isNullOrBlank()) appendLine("- Description (may list film, music director, singers, year): \"${e.description.take(1200)}\"")
        if (e.keywords.isNotEmpty()) appendLine("- Tags: ${e.keywords.take(15).joinToString(", ")}")
        if (!e.note.isNullOrBlank()) appendLine("- Note from the person who shared it: \"${e.note}\"")
    }

    /** Step 1 - web research (Google Search grounding), plain text. */
    fun research(e: SongEvidence): String = buildString {
        appendLine("You are a music researcher who knows Indian film songs (Tamil, Telugu, Malayalam, Kannada, Hindi) and international songs very well.")
        appendLine("Use Google Search to research THIS exact song.")
        appendLine()
        append(facts(e))
        appendLine()
        appendLine("Find and report, saying where each fact came from:")
        appendLine("1. Which song it is: film or album, singers, music director, lyricist.")
        appendLine("2. The OFFICIAL RELEASE DATE (day, month, year) of the song or its film/album, with the source. If you cannot find it, say so plainly - do not guess.")
        appendLine("3. What the lyrics are about: the story, the situation, the feelings, in your own words.")
        appendLine("4. What kind of number it is: romantic duet, soft solo melody, sad/breakup, item number, mass hero-intro, kuthu/folk dance, glamorous/sensual, motivational, devotional, friendship, party...")
        appendLine("5. Whether this YouTube upload is the original release or a later re-upload.")
        append("Plain text, factual, at most 350 words.")
    }

    /** Step 2 - the model listens to the video itself. */
    fun listen(e: SongEvidence): String = buildString {
        appendLine("The attached YouTube video is a song. Listen to all of it and watch it.")
        appendLine()
        append(facts(e))
        appendLine()
        appendLine("Report in plain text (at most 300 words):")
        appendLine("1. The language and a faithful summary of what the lyrics say, line of thought by line of thought (translate the gist into English).")
        appendLine("2. The musical style: tempo, rhythm, instruments, vocal style, production sound - and which period that sound is typical of.")
        appendLine("3. The mood and energy: is it tender, sad, joyful, sensual/steamy, aggressive/mass, a dance number, a prayer?")
        appendLine("4. What the visuals show (romance, glamorous dancing, hero action, crowd dance, devotion) if that tells you something.")
        append("Describe only what you really heard and saw.")
    }

    private fun evidenceBlock(research: String?, listening: String?, webSearch: Boolean): String = buildString {
        appendLine("=== RESEARCH NOTES ${if (webSearch) "(from web search)" else "(web search was NOT available - treat dates with extra care)"} ===")
        appendLine(research?.takeIf { it.isNotBlank() } ?: "(none)")
        appendLine()
        appendLine("=== LISTENING NOTES (from the video itself) ===")
        appendLine(listening?.takeIf { it.isNotBlank() } ?: "(none)")
    }

    private fun rubric(): String = buildString {
        appendLine("MOODS - score EVERY mood from 0 to 10 (0 = does not apply at all, 5 = partly, 8-10 = a defining quality of the song).")
        appendLine("Score each mood separately and independently; one song can score high on several. Do not skip any mood and do not under-score the less common ones: spicy, item and mass numbers matter exactly as much as melodies.")
        appendLine(moodList())
        appendLine()
        appendLine("RELEASE YEAR - the year the song (or its film/album) was officially released:")
        appendLine("- Take it from the research notes. NEVER use the YouTube upload date as the release year (it only proves the song cannot be newer than the upload).")
        appendLine("- If the research does not give it, use your own reliable knowledge. If you truly do not know, return null: a wrong year is worse than none.")
        appendLine("- year_evidence: say exactly where the year comes from. year_confidence: 0 to 1.")
    }

    fun judge(e: SongEvidence, research: String?, listening: String?, webSearch: Boolean): String = buildString {
        appendLine("You sort songs into categories by their MEANING and sound. Be careful and thorough; accuracy matters far more than speed.")
        appendLine()
        append(facts(e))
        appendLine()
        append(evidenceBlock(research, listening, webSearch))
        appendLine()
        append(rubric())
        appendLine()
        append("Answer with JSON only: release_year, year_evidence, year_confidence, lyrics_summary (one or two sentences), mood_scores (every mood id -> 0..10), reasoning (briefly why the top moods fit).")
    }

    fun review(e: SongEvidence, research: String?, listening: String?, webSearch: Boolean, proposal: String): String = buildString {
        appendLine("You are a strict reviewer. A colleague sorted a song; check their work against the evidence and correct every mistake.")
        appendLine()
        append(facts(e))
        appendLine()
        append(evidenceBlock(research, listening, webSearch))
        appendLine()
        appendLine("=== COLLEAGUE'S ANSWER ===")
        appendLine(proposal)
        appendLine()
        append(rubric())
        appendLine()
        appendLine("Mistakes to look for:")
        appendLine("- A release year that is really the upload year, or that contradicts the research notes. A new song must not be placed in an older decade.")
        appendLine("- Spicy / mass, item and dance qualities that were overlooked or under-scored while only melody or love were considered.")
        appendLine("- Moods scored high without support in the lyrics or sound.")
        appendLine()
        append("Give your own corrected final answer as JSON only, in the same format: release_year, year_evidence, year_confidence, lyrics_summary, mood_scores (every mood id -> 0..10), reasoning.")
    }

    /** Gemini structured-output schema for the judge/reviewer answer. */
    fun judgeSchema(): JSONObject {
        val moodProps = JSONObject()
        SongTags.moods.forEach { moodProps.put(it.id, JSONObject().put("type", "INTEGER")) }
        val required = JSONArray().also { a -> SongTags.moods.forEach { a.put(it.id) } }
        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("release_year", JSONObject().put("type", "INTEGER").put("nullable", true))
                .put("year_evidence", JSONObject().put("type", "STRING"))
                .put("year_confidence", JSONObject().put("type", "NUMBER"))
                .put("lyrics_summary", JSONObject().put("type", "STRING"))
                .put("mood_scores", JSONObject().put("type", "OBJECT").put("properties", moodProps).put("required", required))
                .put("reasoning", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("release_year").put("year_confidence").put("mood_scores").put("lyrics_summary"))
    }
}
