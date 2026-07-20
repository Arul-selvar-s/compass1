package com.compass.diary.data.repository

import android.content.Context
import com.compass.diary.data.local.entity.DiaryEntryEntity
import com.compass.diary.data.local.entity.NoteMessageEntity
import com.compass.diary.data.local.entity.PhotoEntity
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.local.entity.StarredItemEntity
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
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun token(): String = withContext(Dispatchers.IO) {
        val signedIn = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw Exception("Not signed in to Google")
        val acct = signedIn.account
            ?: throw Exception("Signed-in account has no Android Account object")
        try {
            com.google.android.gms.auth.GoogleAuthUtil.getToken(context, acct, DRIVE_SCOPE)
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            throw Exception("Google needs you to re-approve Drive access — sign out and sign back in from Settings")
        } catch (e: Exception) {
            throw Exception("Token error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun uploadAll(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tok = token()
            val entries = repo.getAllForBackup()
            val starred = repo.getAllStarredForBackup()
            val songs   = repo.getAllSongsForBackup()
            val notes   = repo.getAllNotesForBackup()
            val photos  = repo.getAllPhotosForBackup()

            val entryArr = JSONArray()
            entries.forEach { e ->
                entryArr.put(JSONObject().apply {
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

            val starredArr = JSONArray()
            starred.forEach { s ->
                starredArr.put(JSONObject().apply {
                    put("diaryDateKey", s.diaryDateKey)
                    put("contentType",  s.contentType)
                    put("contentJson",  s.contentJson)
                    put("preview",      s.preview)
                    put("starredAt",    s.starredAt)
                })
            }

            val songArr = JSONArray()
            songs.forEach { s ->
                songArr.put(JSONObject().apply {
                    put("youtubeUrl", s.youtubeUrl)
                    put("note",       s.note ?: JSONObject.NULL)
                    put("sender",     s.sender)
                    put("sentAt",     s.sentAt)
                })
            }

            val noteArr = JSONArray()
            notes.forEach { n ->
                noteArr.put(JSONObject().apply {
                    put("dateKey", n.dateKey)
                    put("text",    n.text)
                    put("sentAt",  n.sentAt)
                })
            }

            val photoArr = JSONArray()
            photos.forEach { p ->
                photoArr.put(JSONObject().apply {
                    put("dateKey",     p.dateKey)
                    put("fileName",    p.fileName)
                    put("takenAt",     p.takenAt)
                    put("driveFileId", p.driveFileId ?: JSONObject.NULL)
                })
            }

            val body = JSONObject().apply {
                put("entries", entryArr)
                put("starred", starredArr)
                put("songs",   songArr)
                put("notes",   noteArr)
                put("photos",  photoArr)
            }.toString()

            val existingId = findFileId(tok, FILE_NAME)
            if (existingId == null) createJsonFile(tok, FILE_NAME, body) else updateFile(tok, existingId, body)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadAndRestore(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val tok = token()
            val id = findFileId(tok, FILE_NAME) ?: return@withContext Result.success(0)
            val content = downloadFile(tok, id)
            val json = JSONObject(content)

            val entryArr = json.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until entryArr.length()).map { i ->
                val o = entryArr.getJSONObject(i)
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
            repo.mergeFromBackup(entries)

            val starredArr = json.optJSONArray("starred") ?: JSONArray()
            val starred = (0 until starredArr.length()).map { i ->
                val o = starredArr.getJSONObject(i)
                StarredItemEntity(
                    diaryDateKey = o.getString("diaryDateKey"),
                    contentType  = o.optString("contentType", "TEXT"),
                    contentJson  = o.optString("contentJson", ""),
                    preview      = o.optString("preview", ""),
                    starredAt    = o.optLong("starredAt", System.currentTimeMillis())
                )
            }
            repo.mergeStarredFromBackup(starred)

            val songArr = json.optJSONArray("songs") ?: JSONArray()
            val songs = (0 until songArr.length()).map { i ->
                val o = songArr.getJSONObject(i)
                SongMessageEntity(
                    youtubeUrl = o.getString("youtubeUrl"),
                    note       = if (o.isNull("note")) null else o.optString("note"),
                    sender     = o.getString("sender"),
                    sentAt     = o.optLong("sentAt", System.currentTimeMillis())
                )
            }
            repo.mergeSongsFromBackup(songs)

            val noteArr = json.optJSONArray("notes") ?: JSONArray()
            val notes = (0 until noteArr.length()).map { i ->
                val o = noteArr.getJSONObject(i)
                NoteMessageEntity(
                    dateKey = o.getString("dateKey"),
                    text    = o.getString("text"),
                    sentAt  = o.optLong("sentAt", System.currentTimeMillis())
                )
            }
            repo.mergeNotesFromBackup(notes)

            val photoArr = json.optJSONArray("photos") ?: JSONArray()
            val photos = (0 until photoArr.length()).map { i ->
                val o = photoArr.getJSONObject(i)
                PhotoEntity(
                    dateKey     = o.getString("dateKey"),
                    fileName    = o.getString("fileName"),
                    takenAt     = o.optLong("takenAt", System.currentTimeMillis()),
                    driveFileId = if (o.isNull("driveFileId")) null else o.optString("driveFileId")
                )
            }
            val newPhotos = repo.mergePhotosFromBackup(photos)

            val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
            newPhotos.forEach { p ->
                val localFile = File(photosDir, p.fileName)
                if (!localFile.exists() && p.driveFileId != null) {
                    try { downloadBinaryFile(tok, p.driveFileId, localFile) } catch (e: Exception) { /* retry next sync */ }
                }
            }

            Result.success(entries.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadPhotoFile(localFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val tok = token()
            val boundary = "photo_boundary_${System.currentTimeMillis()}"
            val metaJson = JSONObject().apply { put("name", localFile.name) }.toString()
            val imageBytes = localFile.readBytes()

            val out = java.io.ByteArrayOutputStream()
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            out.write(metaJson.toByteArray())
            out.write("\r\n--$boundary\r\n".toByteArray())
            out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
            out.write(imageBytes)
            out.write("\r\n--$boundary--".toByteArray())

            val requestBody = out.toByteArray().toRequestBody("multipart/related; boundary=$boundary".toMediaType())
            val resp = client.newCall(Request.Builder()
                .url("$DRIVE_UPL/files?uploadType=multipart&fields=id")
                .addHeader("Authorization", "Bearer $tok")
                .post(requestBody)
                .build()).execute()
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("Photo upload failed: ${resp.code}"))
            val id = JSONObject(resp.body?.string() ?: "{}").getString("id")
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findFileId(tok: String, name: String): String? {
        val url = "$DRIVE_V3/files?q=name='$name'+and+trashed=false&fields=files(id)"
        val resp = client.newCall(Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $tok").build()).execute()
        if (!resp.isSuccessful) {
            throw Exception("Drive query failed: ${resp.code} ${resp.body?.string()}")
        }
        val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createJsonFile(tok: String, name: String, body: String) {
        val meta = JSONObject().apply { put("name", name) }.toString()
        val mp = "--b\r\nContent-Type: $MIME_JSON\r\n\r\n$meta\r\n--b\r\nContent-Type: $MIME_JSON\r\n\r\n$body\r\n--b--"
        val resp = client.newCall(Request.Builder()
            .url("$DRIVE_UPL/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $tok")
            .post(mp.toRequestBody("multipart/related; boundary=b".toMediaType()))
            .build()).execute()
        if (!resp.isSuccessful) {
            throw Exception("Drive create failed: ${resp.code} ${resp.body?.string()}")
        }
    }

    private fun updateFile(tok: String, id: String, body: String) {
        val resp = client.newCall(Request.Builder()
            .url("$DRIVE_UPL/files/$id?uploadType=media")
            .addHeader("Authorization", "Bearer $tok")
            .patch(body.toRequestBody(MIME_JSON.toMediaType()))
            .build()).execute()
        if (!resp.isSuccessful) {
            throw Exception("Drive update failed: ${resp.code} ${resp.body?.string()}")
        }
    }

    private fun downloadFile(tok: String, id: String): String {
        val resp = client.newCall(Request.Builder()
            .url("$DRIVE_V3/files/$id?alt=media")
            .addHeader("Authorization", "Bearer $tok")
            .build()).execute()
        if (!resp.isSuccessful) {
            throw Exception("Drive download failed: ${resp.code} ${resp.body?.string()}")
        }
        return resp.body?.string() ?: "{}"
    }

    private fun downloadBinaryFile(tok: String, fileId: String, destFile: File) {
        val resp = client.newCall(Request.Builder()
            .url("$DRIVE_V3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $tok")
            .build()).execute()
        if (!resp.isSuccessful) {
            throw Exception("Photo download failed: ${resp.code}")
        }
        destFile.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
    }
}
