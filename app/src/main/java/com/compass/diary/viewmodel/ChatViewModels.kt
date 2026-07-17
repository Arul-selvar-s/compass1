package com.compass.diary.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.data.repository.DriveSync
import com.compass.diary.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    fun sendSong(url: String, note: String?, sender: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        viewModelScope.launch {
            repo.addSong(
                SongMessageEntity(
                    youtubeUrl = cleanUrl,
                    note       = note?.trim()?.ifBlank { null },
                    sender     = sender
                )
            )
            scheduleSync()
        }
    }
}
