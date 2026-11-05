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
