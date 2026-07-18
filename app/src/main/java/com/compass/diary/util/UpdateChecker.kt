package com.compass.diary.util

import com.compass.diary.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseUrl: String
)

@Singleton
class UpdateChecker @Inject constructor() {

    private val client = OkHttpClient()
    private val repo = "Arul-selvar-s/compass1"

    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .addHeader("Accept", "application/vnd.github+json")
                .build()
            val resp = client.newCall(req).execute()

            if (resp.code == 404) return@withContext Result.success(null)
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("GitHub returned ${resp.code}"))

            val json = JSONObject(resp.body?.string() ?: "{}")
            val tag = json.optString("tag_name", "")
            val remoteCode = tag.removePrefix("v").toIntOrNull()
                ?: return@withContext Result.success(null)

            if (remoteCode <= BuildConfig.VERSION_CODE) return@withContext Result.success(null)

            val assets = json.optJSONArray("assets")
            val apkUrl = if (assets != null && assets.length() > 0)
                assets.getJSONObject(0).optString("browser_download_url") else ""

            Result.success(
                UpdateInfo(
                    versionName = json.optString("name", tag),
                    downloadUrl = apkUrl,
                    releaseUrl  = json.optString("html_url", "https://github.com/$repo/releases/latest")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
