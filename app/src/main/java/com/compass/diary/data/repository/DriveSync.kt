package com.compass.diary.data.repository

import android.content.Context
import com.compass.diary.data.local.entity.DiaryEntryEntity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs up ALL diary entries to a single JSON file in the user's Google Drive
 * (file name: compass_diary_backup.json, created via the Drive "drive.file" scope).
 *
 * Requires one-time setup in Google Cloud Console:
 *   • Enable Drive API
 *   • OAuth consent screen configured
 *   • OAuth client ID (Android) registered with package name + SHA-1
 * See README.md for step-by-step instructions.
 */
@Singleton
class DriveSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DiaryRepository
) {
    companion object {
        private const val FILE_NAME   = "compass_diary_backup.json"
        private const val DRIVE_V3    = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPL   = "https://www.googleapis.com/upload/drive/v3"
        private const val MIME_JSON   = "application/json"
        private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun token(): String? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            com.google.android.gms.auth.GoogleAuthUtil.getToken(context, account.account, DRIVE_SCOPE)
        } catch (e: Exception) {
            null
        }
    }

    /** Upload all diary entries to Drive. Called after Save & Lock. */
    suspend fun uploadAll(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tok = token() ?: return@withContext Result.failure(Exception("Not signed in to Google"))
            val entries = repo.getAllForBackup()
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("dateKey",     e.dateKey)
                    put("title",       e.title)
                    put("contentJson", e.contentJson)
                    put("plainText",   e.plainText)
                    put("wordCount",   e.wordCount)
                    put("createdAt",   e.createdAt)
                    put("updatedAt",   e.updatedAt)
                    put("tags",        e.tags)
                })
            }
            val body = JSONObject().apply { put("entries", arr) }.toString()
            val existingId = findFileId(tok)
            if (existingId == null) createFile(tok, body) else updateFile(tok, existingId, body)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Download backup from Drive and restore into local DB. Called right after sign-in. */
    suspend fun downloadAndRestore(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val tok = token() ?: return@withContext Result.failure(Exception("Not signed in to Google"))
            val id = findFileId(tok) ?: return@withContext Result.success(0)
            val content = downloadFile(tok, id)
            val json = JSONObject(content)
            val arr = json.getJSONArray("entries")
            val entries = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DiaryEntryEntity(
                    dateKey     = o.getString("dateKey"),
                    title       = o.getString("title"),
                    contentJson = o.optString("contentJson", ""),
                    plainText   = o.optString("plainText",   ""),
                    wordCount   = o.optInt("wordCount", 0),
                    createdAt   = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt   = o.optLong("updatedAt", System.currentTimeMillis()),
                    tags        = o.optString("tags", "")
                )
            }
            repo.restoreFromBackup(entries)
            Result.success(entries.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findFileId(tok: String): String? {
        val url = "$DRIVE_V3/files?q=name='$FILE_NAME'+and+trashed=false&fields=files(id)"
        val resp = client.newCall(Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $tok").build()).execute()
        val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createFile(tok: String, body: String) {
        val meta = JSONObject().apply { put("name", FILE_NAME) }.toString()
        val mp = "--b\r\nContent-Type: $MIME_JSON\r\n\r\n$meta\r\n--b\r\nContent-Type: $MIME_JSON\r\n\r\n$body\r\n--b--"
        client.newCall(Request.Builder()
            .url("$DRIVE_UPL/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $tok")
            .post(mp.toRequestBody("multipart/related; boundary=b".toMediaType()))
            .build()).execute()
    }

    private fun updateFile(tok: String, id: String, body: String) {
        client.newCall(Request.Builder()
            .url("$DRIVE_UPL/files/$id?uploadType=media")
            .addHeader("Authorization", "Bearer $tok")
            .patch(body.toRequestBody(MIME_JSON.toMediaType()))
            .build()).execute()
    }

    private fun downloadFile(tok: String, id: String): String {
        val resp = client.newCall(Request.Builder()
            .url("$DRIVE_V3/files/$id?alt=media")
            .addHeader("Authorization", "Bearer $tok")
            .build()).execute()
        return resp.body?.string() ?: "{}"
    }
}
