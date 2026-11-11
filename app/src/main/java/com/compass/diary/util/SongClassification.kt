package com.compass.diary.util


enum class SongTagGroup { MOOD, ERA }

/**
 * Every sub-category a song can be sorted into. [id] is what is stored in the database and in the
 * Drive backup, so never change an id once released (labels can change freely).
 */
enum class SongTag(val id: String, val label: String, val group: SongTagGroup, val meaning: String) {
    MELODY("melody", "Melody", SongTagGroup.MOOD, "soft, soothing, tune-led songs you can relax to"),
    LOVE("love", "Love", SongTagGroup.MOOD, "romantic songs about being in love, longing, proposing, a couple's feelings"),
    SAD("sad", "Sad / Breakup", SongTagGroup.MOOD, "heartbreak, separation, pain, loss, loneliness"),
    SPICY("spicy", "Spicy / Mass", SongTagGroup.MOOD, "sensual, steamy, flirty or glamorous songs (bold or suggestive lyrics, seductive dance or visuals) AND high-voltage mass numbers (hero swagger, aggressive beats, punch-line style)"),
    ITEM("item", "Item songs", SongTagGroup.MOOD, "item numbers: a special glamorous dance number, often a guest dancer, club or party style"),
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


/** Which sorting passes a song has been through (stored in the database and in the Drive backup). */
object SongSortPolicy {
    const val TIER_QUICK = 0     // not sorted yet, or sorted by the first quick pass
    const val TIER_DEEP = 2      // deep multi-step analysis
    const val TIER_MANUAL = 9    // edited by hand: never overwritten by the AI
    const val MAX_TRIES = 3

    fun needsSorting(tier: Int, tries: Int): Boolean = tier < TIER_DEEP && tries < MAX_TRIES
}

object Eras {
    /** The era is always DERIVED from the verified release year, never guessed by a model. */
    fun of(year: Int): SongTag = when {
        year <= 1979 -> SongTag.CLASSIC
        year <= 1989 -> SongTag.E80S
        year <= 1999 -> SongTag.E90S
        year <= 2009 -> SongTag.E2000S
        year <= 2019 -> SongTag.E2010S
        else -> SongTag.E2020S
    }
}

object EtaFormat {
    /** 25 min / about 2 h 10 min / under a minute. */
    fun text(ms: Long?): String? {
        if (ms == null || ms < 0) return null
        val minutes = (ms + 30_000L) / 60_000L
        return when {
            ms < 60_000L -> "under a minute"
            minutes < 60 -> "about $minutes min"
            else -> {
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0L) "about $h h" else "about $h h $m min"
            }
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
