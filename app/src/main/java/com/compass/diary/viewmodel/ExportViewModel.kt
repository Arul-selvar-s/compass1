package com.compass.diary.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.util.ExportManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class ExportType { NOTES, SONGS, VOICE, ONE_DAY, FULL }

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val repo: DiaryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _readyToSave = MutableStateFlow(false)
    val readyToSave: StateFlow<Boolean> = _readyToSave

    private var pendingZip: File? = null

    private fun localDateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun inRange(date: LocalDate, from: LocalDate?, to: LocalDate?): Boolean =
        (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))

    fun suggestedFileName(type: ExportType, day: LocalDate?): String {
        val stamp = day?.toString() ?: LocalDate.now().toString()
        return when (type) {
            ExportType.NOTES  -> "compass_notes_$stamp.zip"
            ExportType.SONGS  -> "compass_songs_$stamp.zip"
            ExportType.VOICE  -> "compass_voice_$stamp.zip"
            ExportType.ONE_DAY -> "compass_day_${day ?: LocalDate.now()}.zip"
            ExportType.FULL   -> "compass_full_backup_$stamp.zip"
        }
    }

    fun exportNotes(from: LocalDate?, to: LocalDate?) {
        viewModelScope.launch {
            _isExporting.value = true; _status.value = "Preparing…"
            val filtered = repo.getAllNotesForBackup().filter { inRange(localDateOf(it.sentAt), from, to) }
            val moods = repo.getAllMoodForBackup().filter {
                val d = runCatching { LocalDate.parse(it.dateKey) }.getOrNull()
                d != null && inRange(d, from, to)
            }
            val workDir = freshWorkDir()
            File(workDir, "notes.csv").writeText(ExportManager.notesCsv(filtered))
            File(workDir, "mood.csv").writeText(ExportManager.moodCsv(moods))
            finishZip(workDir, "notes")
        }
    }

    fun exportSongs(from: LocalDate?, to: LocalDate?, sender: String?) {
        viewModelScope.launch {
            _isExporting.value = true; _status.value = "Preparing…"
            var filtered = repo.getAllSongsForBackup().filter { inRange(localDateOf(it.sentAt), from, to) }
            if (sender != null) filtered = filtered.filter { it.sender == sender }
            val workDir = freshWorkDir()
            File(workDir, "songs.csv").writeText(ExportManager.songsCsv(filtered))
            finishZip(workDir, "songs")
        }
    }

    fun exportVoice(from: LocalDate?, to: LocalDate?) {
        viewModelScope.launch {
            _isExporting.value = true; _status.value = "Preparing…"
            val filtered = repo.getAllVoiceForBackup().filter { inRange(localDateOf(it.sentAt), from, to) }
            val workDir = freshWorkDir()
            File(workDir, "voice_manifest.csv").writeText(ExportManager.voiceManifestCsv(filtered))
            val voiceDir = File(context.filesDir, "voice")
            val outVoiceDir = File(workDir, "voice").apply { mkdirs() }
            filtered.forEach { v ->
                val src = File(voiceDir, v.audioFileName)
                if (src.exists()) src.copyTo(File(outVoiceDir, v.audioFileName), overwrite = true)
            }
            finishZip(workDir, "voice")
        }
    }

    fun exportOneDay(day: LocalDate) {
        viewModelScope.launch {
            _isExporting.value = true; _status.value = "Preparing…"
            val dateKey = day.toString()
            val notes  = repo.getAllNotesForBackup().filter { n -> n.dateKey == dateKey }
            val songs  = repo.getAllSongsForBackup().filter { localDateOf(it.sentAt) == day }
            val voice  = repo.getAllVoiceForBackup().filter { localDateOf(it.sentAt) == day }
            val photos = repo.getAllPhotosForBackup().filter { it.dateKey == dateKey }
            val mood   = repo.getAllMoodForBackup().filter { it.dateKey == dateKey }

            val workDir = freshWorkDir()
            File(workDir, "notes.csv").writeText(ExportManager.notesCsv(notes))
            File(workDir, "songs.csv").writeText(ExportManager.songsCsv(songs))
            File(workDir, "voice_manifest.csv").writeText(ExportManager.voiceManifestCsv(voice))
            File(workDir, "mood.csv").writeText(ExportManager.moodCsv(mood))

            val voiceSrcDir = File(context.filesDir, "voice")
            val outVoiceDir = File(workDir, "voice").apply { mkdirs() }
            voice.forEach { v ->
                val src = File(voiceSrcDir, v.audioFileName)
                if (src.exists()) src.copyTo(File(outVoiceDir, v.audioFileName), overwrite = true)
            }

            val photosSrcDir = File(context.filesDir, "photos")
            val outPhotosDir = File(workDir, "photos").apply { mkdirs() }
            photos.forEach { p ->
                val src = File(photosSrcDir, p.fileName)
                if (src.exists()) src.copyTo(File(outPhotosDir, p.fileName), overwrite = true)
            }

            finishZip(workDir, "day_$dateKey")
        }
    }

    fun exportFull(from: LocalDate?, to: LocalDate?) {
        viewModelScope.launch {
            _isExporting.value = true; _status.value = "Preparing… this may take a moment"
            val notes  = repo.getAllNotesForBackup().filter { inRange(localDateOf(it.sentAt), from, to) }
            val songs  = repo.getAllSongsForBackup().filter { inRange(localDateOf(it.sentAt), from, to) }
            val voice  = repo.getAllVoiceForBackup().filter { inRange(localDateOf(it.sentAt), from, to) }
            val photos = repo.getAllPhotosForBackup().filter {
                val d = runCatching { LocalDate.parse(it.dateKey) }.getOrNull()
                d != null && inRange(d, from, to)
            }
            val moods = repo.getAllMoodForBackup().filter {
                val d = runCatching { LocalDate.parse(it.dateKey) }.getOrNull()
                d != null && inRange(d, from, to)
            }

            val allDates = (notes.map { it.dateKey } + songs.map { localDateOf(it.sentAt).toString() } +
                    voice.map { localDateOf(it.sentAt).toString() } + photos.map { it.dateKey } +
                    moods.map { it.dateKey }).toSortedSet()

            val workDir = freshWorkDir()
            val voiceSrcDir = File(context.filesDir, "voice")
            val photosSrcDir = File(context.filesDir, "photos")

            allDates.forEach { dateKey ->
                val dayDir = File(workDir, dateKey).apply { mkdirs() }
                val dayDate = runCatching { LocalDate.parse(dateKey) }.getOrNull()

                File(dayDir, "notes.csv").writeText(ExportManager.notesCsv(notes.filter { it.dateKey == dateKey }))
                File(dayDir, "songs.csv").writeText(ExportManager.songsCsv(songs.filter { dayDate != null && localDateOf(it.sentAt) == dayDate }))
                File(dayDir, "mood.csv").writeText(ExportManager.moodCsv(moods.filter { it.dateKey == dateKey }))
                val dayVoice = voice.filter { dayDate != null && localDateOf(it.sentAt) == dayDate }
                File(dayDir, "voice_manifest.csv").writeText(ExportManager.voiceManifestCsv(dayVoice))
                val outVoiceDir = File(dayDir, "voice").apply { mkdirs() }
                dayVoice.forEach { v ->
                    val src = File(voiceSrcDir, v.audioFileName)
                    if (src.exists()) src.copyTo(File(outVoiceDir, v.audioFileName), overwrite = true)
                }
                val dayPhotos = photos.filter { it.dateKey == dateKey }
                if (dayPhotos.isNotEmpty()) {
                    val outPhotosDir = File(dayDir, "photos").apply { mkdirs() }
                    dayPhotos.forEach { p ->
                        val src = File(photosSrcDir, p.fileName)
                        if (src.exists()) src.copyTo(File(outPhotosDir, p.fileName), overwrite = true)
                    }
                }
            }
            finishZip(workDir, "full_backup")
        }
    }

    private fun freshWorkDir(): File =
        File(context.cacheDir, "export_${System.currentTimeMillis()}").apply { mkdirs() }

    private fun finishZip(workDir: File, label: String) {
        _status.value = "Compressing & encrypting…"
        val zip = File(context.cacheDir, "compass_export_$label.zip")
        ExportManager.createPasswordProtectedZip(workDir, zip)
        workDir.deleteRecursively()
        pendingZip = zip
        _status.value = "Ready — choose where to save"
        _isExporting.value = false
        _readyToSave.value = true
    }

    fun writeZipTo(uri: Uri) {
        viewModelScope.launch {
            val zip = pendingZip ?: return@launch
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    zip.inputStream().use { it.copyTo(out) }
                }
                _status.value = "Saved ✓ — password is 0512"
            } catch (e: Exception) {
                _status.value = "Save failed: ${e.message}"
            }
            _readyToSave.value = false
        }
    }

    fun cancelPendingSave() {
        _readyToSave.value = false
        _status.value = null
    }
}
