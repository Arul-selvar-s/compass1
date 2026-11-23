package com.compass.diary.data.repository

import android.content.Context
import com.compass.diary.data.local.entity.DiaryEntryEntity
import com.compass.diary.data.local.entity.MoodEntity
import com.compass.diary.data.local.entity.NoteMessageEntity
import com.compass.diary.data.local.entity.PhotoEntity
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.local.entity.StarredItemEntity
import com.compass.diary.util.PreferencesManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val repo: DiaryRepository,
    private val prefs: PreferencesManager
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

    // One upload/download at a time, whoever asks (screens, WorkManager, the 10s poll).
    // Without this, two overlapping downloads could both insert the same note.
    private val syncLock = Mutex()

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

    private class DriveHttpException(val code: Int, message: String) : Exception(message)

    /** The backup file on Drive; [stamp] changes every time anyone writes to it. */
    private data class RemoteFile(val id: String, val stamp: String)

    /** Runs [block] with a Drive token; if Google says the token is stale (401) it is thrown away and the call repeated once. */
    private suspend fun <T> authed(block: suspend (String) -> T): T {
        val tok = token()
        return try {
            block(tok)
        } catch (e: DriveHttpException) {
            if (e.code != 401) throw e
            withContext(Dispatchers.IO) {
                try { com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, tok) } catch (ignored: Exception) { }
            }
            block(token())
        }
    }

    private suspend fun recordFailure(e: Exception) {
        val msg = when (e) {
            is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "No internet connection"
            else -> (e.message ?: e.javaClass.simpleName).take(140)
        }
        prefs.setLastSyncError(msg)
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Uploads this phone's data. [onlyIfChanged] = skip the upload when nothing changed since the last one.
     * If the other phone changed the backup since we last looked, its changes are taken in first, so an
     * upload can never wipe them out (and a fresh install never overwrites the backup with an empty diary).
     */
    suspend fun uploadAll(onlyIfChanged: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        syncLock.withLock {
            try {
                authed { tok -> push(tok, onlyIfChanged) }
                prefs.setLastSyncError(null)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(e)
                Result.failure(e)
            }
        }
    }

    private suspend fun push(tok: String, onlyIfChanged: Boolean) {
        val remote = findFile(tok, FILE_NAME)
        if (remote != null && remote.stamp != prefs.remoteStamp.first()) pull(tok, force = true)

        val photosDir = File(context.filesDir, "photos")
        repo.getAllPhotosForBackup().forEach { p ->
            if (p.driveFileId == null) {
                val localFile = File(photosDir, p.fileName)
                if (localFile.exists()) {
                    val uploadedId = uploadPhotoBytes(tok, localFile)
                    if (uploadedId != null) repo.setPhotoDriveFileId(p.id, uploadedId)
                }
            }
        }

        val entries = repo.getAllForBackup()
        val starred = repo.getAllStarredForBackup()
        val songs   = repo.getAllSongsForBackup()
        val notes   = repo.getAllNotesForBackup()
        val photos  = repo.getAllPhotosForBackup()
        val moods   = repo.getAllMoodForBackup()

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
                put("title",      s.title ?: JSONObject.NULL)
                put("categories",   s.categories ?: JSONObject.NULL)
                put("categoriesAt", s.categoriesAt)
                put("categoryTier", s.categoryTier)
            })
        }

        val noteArr = JSONArray()
        notes.forEach { n ->
            noteArr.put(JSONObject().apply {
                put("dateKey",  n.dateKey)
                put("text",     n.text)
                put("sentAt",   n.sentAt)
                put("reaction", n.reaction ?: JSONObject.NULL)
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

        val moodArr = JSONArray()
        moods.forEach { m ->
            moodArr.put(JSONObject().apply {
                put("dateKey",        m.dateKey)
                put("missedPercent",  m.missedPercent)
                put("lovedPercent",   m.lovedPercent)
                put("savedAt",        m.savedAt)
            })
        }

        val body = JSONObject().apply {
            put("entries", entryArr)
            put("starred", starredArr)
            put("songs",   songArr)
            put("notes",   noteArr)
            put("photos",  photoArr)
            put("moods",   moodArr)
        }.toString()

        val hash = sha256(body)
        if (onlyIfChanged && remote != null && hash == prefs.pushedHash.first()) {
            prefs.setLastSync(System.currentTimeMillis())   // checked, and nothing needed uploading
            return
        }

        val newStamp = if (remote == null) createJsonFile(tok, FILE_NAME, body) else updateFile(tok, remote.id, body)
        prefs.setRemoteStamp(newStamp)
        prefs.setPushedHash(hash)
        prefs.setLastSync(System.currentTimeMillis())
    }

    /**
     * Takes in the other phone's changes. Cheap when nothing changed: it only asks Drive for the backup's
     * version and downloads the file when that differs from what this phone already merged.
     * [force] = download anyway (the manual refresh button).
     */
    suspend fun downloadAndRestore(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        syncLock.withLock {
            try {
                val merged = authed { tok -> pull(tok, force) }
                prefs.setLastSyncError(null)
                Result.success(merged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(e)
                Result.failure(e)
            }
        }
    }

    private suspend fun pull(tok: String, force: Boolean): Int {
        val remote = findFile(tok, FILE_NAME)
        if (remote == null || (!force && remote.stamp == prefs.remoteStamp.first())) {
            prefs.setLastSync(System.currentTimeMillis())   // checked: this phone is up to date
            return 0
        }
        val content = downloadFile(tok, remote.id)
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
                sentAt     = o.optLong("sentAt", System.currentTimeMillis()),
                title      = if (o.isNull("title")) null else o.optString("title"),
                categories   = if (o.isNull("categories")) null else o.optString("categories"),
                categoriesAt = o.optLong("categoriesAt", 0L),
                categoryTier = o.optInt("categoryTier", 0)
            )
        }
        repo.mergeSongsFromBackup(songs)

        val noteArr = json.optJSONArray("notes") ?: JSONArray()
        val notes = (0 until noteArr.length()).map { i ->
            val o = noteArr.getJSONObject(i)
            NoteMessageEntity(
                dateKey  = o.getString("dateKey"),
                text     = o.getString("text"),
                sentAt   = o.optLong("sentAt", System.currentTimeMillis()),
                reaction = if (o.isNull("reaction")) null else o.optString("reaction").ifBlank { null }
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
        val needDownload = repo.mergePhotosFromBackup(photos)

        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        var failedPhotos = 0
        needDownload.forEach { p ->
            val localFile = File(photosDir, p.fileName)
            if (!localFile.exists() && p.driveFileId != null) {
                try { downloadBinaryFile(tok, p.driveFileId, localFile) } catch (e: Exception) { failedPhotos++ }
            }
        }

        val moodArr = json.optJSONArray("moods") ?: JSONArray()
        val moods = (0 until moodArr.length()).map { i ->
            val o = moodArr.getJSONObject(i)
            MoodEntity(
                dateKey       = o.getString("dateKey"),
                missedPercent = o.optInt("missedPercent", 0),
                lovedPercent  = o.optInt("lovedPercent", 0),
                savedAt       = o.optLong("savedAt", System.currentTimeMillis())
            )
        }
        repo.mergeMoodFromBackup(moods)

        // A photo that could not be fetched is retried by the next check instead of being forgotten.
        if (failedPhotos == 0) prefs.setRemoteStamp(remote.stamp)
        prefs.setLastSync(System.currentTimeMillis())
        return entries.size
    }

    suspend fun uploadPhotoFile(localFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val tok = token()
            val id = uploadPhotoBytes(tok, localFile) ?: return@withContext Result.failure(Exception("Photo upload failed"))
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun uploadPhotoBytes(tok: String, localFile: File): String? {
        return try {
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
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string() ?: "{}").getString("id")
        } catch (e: Exception) {
            null
        }
    }

    private fun findFile(tok: String, name: String): RemoteFile? {
        // Newest first: if two backup files ever exist (two phones signing in at the same moment), everyone uses the newest.
        val url = "$DRIVE_V3/files?q=name='$name'+and+trashed=false&orderBy=modifiedTime+desc&fields=files(id,version,modifiedTime)"
        return client.newCall(Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $tok").build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw DriveHttpException(resp.code, "Drive query failed: ${resp.code} $text")
            val files = JSONObject(text).optJSONArray("files")
            if (files == null || files.length() == 0) null
            else files.getJSONObject(0).let { f -> RemoteFile(f.getString("id"), stampOf(f)) }
        }
    }

    private fun stampOf(f: JSONObject): String = "${f.optString("version")}|${f.optString("modifiedTime")}"

    private fun createJsonFile(tok: String, name: String, body: String): String {
        val meta = JSONObject().apply { put("name", name) }.toString()
        val mp = "--b\r\nContent-Type: $MIME_JSON\r\n\r\n$meta\r\n--b\r\nContent-Type: $MIME_JSON\r\n\r\n$body\r\n--b--"
        return client.newCall(Request.Builder()
            .url("$DRIVE_UPL/files?uploadType=multipart&fields=id,version,modifiedTime")
            .addHeader("Authorization", "Bearer $tok")
            .post(mp.toRequestBody("multipart/related; boundary=b".toMediaType()))
            .build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw DriveHttpException(resp.code, "Drive create failed: ${resp.code} $text")
            stampOf(JSONObject(text))
        }
    }

    private fun updateFile(tok: String, id: String, body: String): String {
        return client.newCall(Request.Builder()
            .url("$DRIVE_UPL/files/$id?uploadType=media&fields=id,version,modifiedTime")
            .addHeader("Authorization", "Bearer $tok")
            .patch(body.toRequestBody(MIME_JSON.toMediaType()))
            .build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw DriveHttpException(resp.code, "Drive update failed: ${resp.code} $text")
            stampOf(JSONObject(text))
        }
    }

    private fun downloadFile(tok: String, id: String): String {
        return client.newCall(Request.Builder()
            .url("$DRIVE_V3/files/$id?alt=media")
            .addHeader("Authorization", "Bearer $tok")
            .build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw DriveHttpException(resp.code, "Drive download failed: ${resp.code} $text")
            text
        }
    }

    private fun downloadBinaryFile(tok: String, fileId: String, destFile: File) {
        val resp = client.newCall(Request.Builder()
            .url("$DRIVE_V3/files/$fileId?alt=media")
            .addHeader("Authorization", "Bearer $tok")
            .build()).execute()
        if (!resp.isSuccessful) {
            throw DriveHttpException(resp.code, "Photo download failed: ${resp.code}")
        }
        destFile.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
    }
}
