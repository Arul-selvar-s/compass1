package com.compass.diary.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerState(
    val songKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

/**
 * Remembers the last song + position. Saved locally (DataStore) and to a tiny
 * Drive file so both phones resume from wherever either of you stopped.
 */
@Singleton
class PlayerStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager
) {
    companion object {
        private val KEY_SONG = stringPreferencesKey("player_song_key")
        private val KEY_POS  = longPreferencesKey("player_position_ms")
        private val KEY_DUR  = longPreferencesKey("player_duration_ms")
        private val KEY_AT   = longPreferencesKey("player_updated_at")

        private const val FILE_NAME  = "compass_player_state.json"
        private const val DRIVE_V3   = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPL  = "https://www.googleapis.com/upload/drive/v3"
        private const val MIME_JSON  = "application/json"
        private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private var cachedFileId: String? = null

    // Saves are processed strictly in order, on the store's own scope, so they
    // still finish after the player screen (and its ViewModel) is gone.
    private val queue = Channel<Pair<PlayerState, Boolean>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((state, upload) in queue) {
                try {
                    context.dataStore.edit {
                        it[KEY_SONG] = state.songKey
                        it[KEY_POS]  = state.positionMs
                        it[KEY_DUR]  = state.durationMs
                        it[KEY_AT]   = state.updatedAt
                    }
                } catch (e: Exception) { /* ignore */ }
                if (upload) uploadRemote(state)
            }
        }
    }

    fun save(state: PlayerState, upload: Boolean) {
        queue.trySend(state to upload)
    }

    private suspend fun loadLocal(): PlayerState? {
        val p = context.dataStore.data.first()
        val key = p[KEY_SONG] ?: return null
        return PlayerState(key, p[KEY_POS] ?: 0L, p[KEY_DUR] ?: 0L, p[KEY_AT] ?: 0L)
    }

    /** Newest of (this phone's copy, Drive's copy). Falls back to local if offline. */
    suspend fun loadBest(): PlayerState? {
        val local = runCatching { loadLocal() }.getOrNull()
        val remote = withTimeoutOrNull(7000) { runCatching { loadRemote() }.getOrNull() }
        return listOfNotNull(local, remote).maxByOrNull { it.updatedAt }
    }

    private suspend fun syncEnabled(): Boolean {
        val account = prefs.googleAccount.first()
        val enabled = prefs.isAutoSyncEnabled.first()
        return !account.isNullOrBlank() && enabled
    }

    private fun token(): String {
        val signedIn = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw Exception("Not signed in to Google")
        val acct = signedIn.account ?: throw Exception("No account")
        return GoogleAuthUtil.getToken(context, acct, DRIVE_SCOPE)
    }

    private suspend fun loadRemote(): PlayerState? = withContext(Dispatchers.IO) {
        if (!syncEnabled()) return@withContext null
        val tok = token()
        val id = cachedFileId ?: findFileId(tok) ?: return@withContext null
        cachedFileId = id
        val resp = client.newCall(
            Request.Builder().url("$DRIVE_V3/files/$id?alt=media")
                .addHeader("Authorization", "Bearer $tok").build()
        ).execute()
        if (!resp.isSuccessful) { cachedFileId = null; return@withContext null }
        val o = JSONObject(resp.body?.string() ?: return@withContext null)
        PlayerState(
            songKey    = o.getString("songKey"),
            positionMs = o.optLong("positionMs", 0L),
            durationMs = o.optLong("durationMs", 0L),
            updatedAt  = o.optLong("updatedAt", 0L)
        )
    }

    private suspend fun uploadRemote(state: PlayerState) {
        try {
            if (!syncEnabled()) return
            val tok = token()
            val body = JSONObject().apply {
                put("songKey", state.songKey)
                put("positionMs", state.positionMs)
                put("durationMs", state.durationMs)
                put("updatedAt", state.updatedAt)
            }.toString()

            val id = cachedFileId ?: findFileId(tok)
            if (id == null) {
                cachedFileId = createFile(tok, body)
            } else {
                cachedFileId = id
                val resp = client.newCall(
                    Request.Builder().url("$DRIVE_UPL/files/$id?uploadType=media")
                        .addHeader("Authorization", "Bearer $tok")
                        .patch(body.toRequestBody(MIME_JSON.toMediaType())).build()
                ).execute()
                if (!resp.isSuccessful) cachedFileId = null
            }
        } catch (e: Exception) {
            cachedFileId = null
        }
    }

    private fun findFileId(tok: String): String? {
        val url = "$DRIVE_V3/files?q=name='$FILE_NAME'+and+trashed=false&fields=files(id)"
        val resp = client.newCall(
            Request.Builder().url(url).addHeader("Authorization", "Bearer $tok").build()
        ).execute()
        if (!resp.isSuccessful) return null
        val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createFile(tok: String, body: String): String? {
        val meta = JSONObject().apply { put("name", FILE_NAME) }.toString()
        val mp = "--b\r\nContent-Type: $MIME_JSON\r\n\r\n$meta\r\n--b\r\nContent-Type: $MIME_JSON\r\n\r\n$body\r\n--b--"
        val resp = client.newCall(
            Request.Builder().url("$DRIVE_UPL/files?uploadType=multipart&fields=id")
                .addHeader("Authorization", "Bearer $tok")
                .post(mp.toRequestBody("multipart/related; boundary=b".toMediaType())).build()
        ).execute()
        if (!resp.isSuccessful) return null
        return JSONObject(resp.body?.string() ?: "{}").optString("id").ifBlank { null }
    }
}
