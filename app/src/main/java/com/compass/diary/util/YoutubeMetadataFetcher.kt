package com.compass.diary.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object YoutubeMetadataFetcher {
    private val client = OkHttpClient()

    data class Info(val title: String?, val channel: String?)

    /** Everything useful for judging a song: oEmbed title/channel plus upload date, description and tags from the watch page. */
    data class Details(
        val title: String?,
        val channel: String?,
        val uploadDate: String?,
        val description: String?,
        val keywords: List<String>
    )

    suspend fun fetchDetails(youtubeUrl: String): Details? = withContext(Dispatchers.IO) {
        val info = fetchInfo(youtubeUrl)
        val id = YoutubeIds.extract(youtubeUrl)
        var page: YoutubePageParser.Page? = null
        if (id != null) {
            page = try {
                val req = Request.Builder()
                    .url("https://www.youtube.com/watch?v=$id&hl=en")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) YoutubePageParser.parse(resp.body?.string() ?: "") else null
                }
            } catch (e: Exception) {
                null
            }
        }
        if (info == null && page == null) null
        else Details(info?.title, info?.channel, page?.uploadDate, page?.description, page?.keywords ?: emptyList())
    }

    /** Title and channel name (oEmbed). null when YouTube cannot be reached or the video is private. */
    suspend fun fetchInfo(youtubeUrl: String): Info? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(youtubeUrl, "UTF-8")
            val req = Request.Builder()
                .url("https://www.youtube.com/oembed?url=$encoded&format=json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: "{}")
                Info(
                    title = json.optString("title", "").ifBlank { null },
                    channel = json.optString("author_name", "").ifBlank { null }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchTitle(youtubeUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(youtubeUrl, "UTF-8")
            val req = Request.Builder()
                .url("https://www.youtube.com/oembed?url=$encoded&format=json")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val json = JSONObject(resp.body?.string() ?: "{}")
            json.optString("title", "").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}


/** Reads the facts out of a YouTube watch page (best effort: any field may be missing). */
object YoutubePageParser {
    data class Page(val uploadDate: String?, val description: String?, val keywords: List<String>)

    private val publish = Regex(""""publishDate":"(\d{4}-\d{2}-\d{2})""")
    private val upload = Regex(""""uploadDate":"(\d{4}-\d{2}-\d{2})""")
    private val shortDescription = Regex(""""shortDescription":"((?:[^"\\]|\\.)*)"""")
    private val keywordsArray = Regex(""""keywords":\[((?:[^\]])*)\]""")
    private val quoted = Regex(""""((?:[^"\\]|\\.)*)"""")

    fun parse(html: String): Page {
        val date = publish.find(html)?.groupValues?.get(1) ?: upload.find(html)?.groupValues?.get(1)
        val description = shortDescription.find(html)?.groupValues?.get(1)?.let { unescape(it) }?.takeIf { it.isNotBlank() }
        val keywords = keywordsArray.find(html)?.groupValues?.get(1)
            ?.let { arr -> quoted.findAll(arr).map { unescape(it.groupValues[1]) }.filter { it.isNotBlank() }.toList() }
            ?: emptyList()
        return Page(date, description, keywords)
    }

    private fun unescape(raw: String): String = try {
        org.json.JSONObject("{\"v\":\"$raw\"}").getString("v")
    } catch (e: Exception) {
        raw
    }
}
