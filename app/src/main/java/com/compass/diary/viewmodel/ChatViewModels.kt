package com.compass.diary.viewmodel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.local.entity.VoiceMessageEntity
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.data.repository.DriveSync
import com.compass.diary.util.AudioCompressor
import com.compass.diary.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SongViewModel @Inject constructor(
    private val repo: DiaryRepository,
    private val driveSync: DriveSync,
    private val prefs: PreferencesManager
) : ViewModel() {

    val songs: StateFlow<List<SongMessageEntity>> = repo.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var syncJob: kotlinx.coroutines.Job? = null
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            val account = prefs.googleAccount.first()
            val enabled = prefs.isAutoSyncEnabled.first()
            if (!account.isNullOrBlank() && enabled) driveSync.uploadAll()
        }
    }

    fun sendSong(url: String, note: String?, sender: String, sentAt: Long = System.currentTimeMillis()) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val cleanNote: String? = note?.trim()?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            repo.addSong(
                SongMessageEntity(
                    youtubeUrl = cleanUrl,
                    note       = cleanNote,
                    sender     = sender,
                    sentAt     = sentAt
                )
            )
            scheduleSync()
        }
    }
}

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val repo: DiaryRepository,
    private val driveSync: DriveSync,
    private val prefs: PreferencesManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val messages: StateFlow<List<VoiceMessageEntity>> = repo.getAllVoiceMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null

    private fun voiceDir(): File = File(context.filesDir, "voice").apply { mkdirs() }

    fun startRecording() {
        val file = File(voiceDir(), "voice_${System.currentTimeMillis()}.m4a")
        try {
            @Suppress("DEPRECATION")
            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recordingFile = file
            _isRecording.value = true
        } catch (e: Exception) {
            _isRecording.value = false
        }
    }

    fun stopRecordingAndSave(note: String?, sentAt: Long = System.currentTimeMillis()) {
        val file = recordingFile
        try { recorder?.stop() } catch (e: Exception) { /* ignore */ }
        try { recorder?.release() } catch (e: Exception) { /* ignore */ }
        recorder = null
        _isRecording.value = false

        val cleanNote: String? = note?.trim()?.takeIf { it.isNotBlank() }

        if (file != null && file.exists() && file.length() > 0) {
            viewModelScope.launch {
                val duration = durationOf(file)
                repo.addVoiceMessage(
                    VoiceMessageEntity(
                        audioFileName = file.name,
                        note          = cleanNote,
                        durationMs    = duration,
                        sourceType    = "RECORDED",
                        sentAt        = sentAt
                    )
                )
                scheduleSync()
            }
        }
        recordingFile = null
    }

    fun cancelRecording() {
        try { recorder?.stop() } catch (e: Exception) { /* ignore */ }
        try { recorder?.release() } catch (e: Exception) { /* ignore */ }
        recorder = null
        _isRecording.value = false
        recordingFile?.delete()
        recordingFile = null
    }

    /** Imports always go through AudioCompressor — enforces the "always compress" rule. */
    fun importAudio(uri: Uri, note: String?, sentAt: Long = System.currentTimeMillis()) {
        val cleanNote: String? = note?.trim()?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            _isProcessing.value = true
            _importError.value = null
            val outFile = File(voiceDir(), "voice_${System.currentTimeMillis()}.m4a")
            val error = AudioCompressor.compressToAac(context, uri, outFile)
            if (error == null && outFile.exists() && outFile.length() > 0) {
                val duration = durationOf(outFile)
                repo.addVoiceMessage(
                    VoiceMessageEntity(
                        audioFileName = outFile.name,
                        note          = cleanNote,
                        durationMs    = duration,
                        sourceType    = "IMPORTED",
                        sentAt        = sentAt
                    )
                )
                scheduleSync()
            } else {
                outFile.delete()
                _importError.value = error ?: "Import failed — unknown error"
            }
            _isProcessing.value = false
        }
    }

    fun clearImportError() { _importError.value = null }

    private fun durationOf(file: File): Long = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        retriever.release()
        d
    } catch (e: Exception) { 0L }

    fun togglePlay(msg: VoiceMessageEntity) {
        if (_playingId.value == msg.id) {
            player?.pause()
            _playingId.value = null
            return
        }
        player?.release()
        val file = File(voiceDir(), msg.audioFileName)
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { _playingId.value = null }
                prepare()
                start()
            }
            _playingId.value = msg.id
        } catch (e: Exception) {
            _playingId.value = null
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null
    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            val account = prefs.googleAccount.first()
            val enabled = prefs.isAutoSyncEnabled.first()
            if (!account.isNullOrBlank() && enabled) driveSync.uploadAll()
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { player?.release() } catch (e: Exception) { /* ignore */ }
        syncJob?.cancel()
    }
}
