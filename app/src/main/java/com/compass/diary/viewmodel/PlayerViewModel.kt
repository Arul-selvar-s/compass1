package com.compass.diary.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.util.PlayerNotificationManager
import com.compass.diary.util.YoutubeMetadataFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PlayerCategory { ALL, JENMASANI, KUTTY_GOLU }

interface YoutubePlayerController {
    fun loadAndPlay(videoId: String)
    fun play()
    fun pause()
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repo: DiaryRepository,
    private val notificationManager: PlayerNotificationManager
) : ViewModel() {

    private val allSongs: StateFlow<List<SongMessageEntity>> = repo.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _category = MutableStateFlow(PlayerCategory.ALL)
    val category: StateFlow<PlayerCategory> = _category

    private val _currentList = MutableStateFlow<List<SongMessageEntity>>(emptyList())
    val currentList: StateFlow<List<SongMessageEntity>> = _currentList

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    val currentSong: StateFlow<SongMessageEntity?> = combine(_currentList, _currentIndex) { list, idx ->
        list.getOrNull(idx)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _shuffleOn = MutableStateFlow(false)
    val shuffleOn: StateFlow<Boolean> = _shuffleOn

    private val _repeatOneOn = MutableStateFlow(false)
    val repeatOneOn: StateFlow<Boolean> = _repeatOneOn

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val searchResults: StateFlow<List<SongMessageEntity>> = combine(_currentList, _searchQuery) { list, query ->
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else list.filter { song ->
            (song.title?.contains(q, ignoreCase = true) == true) ||
                (song.note?.contains(q, ignoreCase = true) == true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun clearSearch() { _searchQuery.value = "" }

    fun playSearchResult(song: SongMessageEntity) {
        val idx = _currentList.value.indexOf(song)
        if (idx >= 0) playAt(idx)
        clearSearch()
    }

    var controller: YoutubePlayerController? = null

    init {
        viewModelScope.launch {
            allSongs.collect { songs ->
                refreshList(songs)
                backfillMissingTitles(songs)
            }
        }
        viewModelScope.launch {
            combine(currentSong, isPlaying) { song, playing -> song to playing }
                .collect { (song, playing) ->
                    if (song != null) {
                        val sender = if (song.sender == "JENMASANI") "Jenmasani" else "Kutty Golu"
                        notificationManager.show(if (playing) "Now playing" else "Paused", "Sent by $sender", playing)
                    } else {
                        notificationManager.cancel()
                    }
                }
        }
    }

    private fun backfillMissingTitles(songs: List<SongMessageEntity>) {
        songs.filter { it.title.isNullOrBlank() }.forEach { song ->
            viewModelScope.launch {
                val title = YoutubeMetadataFetcher.fetchTitle(song.youtubeUrl)
                if (title != null) repo.setSongTitle(song.id, title)
            }
        }
    }

    private fun filteredSorted(cat: PlayerCategory, songs: List<SongMessageEntity>): List<SongMessageEntity> {
        val filtered = when (cat) {
            PlayerCategory.ALL        -> songs
            PlayerCategory.JENMASANI  -> songs.filter { it.sender == "JENMASANI" }
            PlayerCategory.KUTTY_GOLU -> songs.filter { it.sender == "KUTTY_GOLU" }
        }
        return filtered.sortedBy { it.sentAt }
    }

    private fun refreshList(songs: List<SongMessageEntity>) {
        val filtered = filteredSorted(_category.value, songs)
        val currentId = _currentList.value.getOrNull(_currentIndex.value)?.id
        _currentList.value = filtered
        if (currentId != null) {
            val newIdx = filtered.indexOfFirst { it.id == currentId }
            if (newIdx >= 0) _currentIndex.value = newIdx
        }
    }

    fun selectCategory(cat: PlayerCategory) {
        _category.value = cat
        clearSearch()
        val filtered = filteredSorted(cat, allSongs.value)
        _currentList.value = filtered
        if (filtered.isNotEmpty()) {
            playAt(filtered.size - 1)
        } else {
            _currentIndex.value = -1
            _isPlaying.value = false
        }
    }

    fun playAt(index: Int) {
        val list = _currentList.value
        if (index !in list.indices) return
        _currentIndex.value = index
        _isPlaying.value = true
        controller?.loadAndPlay(extractVideoId(list[index].youtubeUrl))
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            controller?.pause(); _isPlaying.value = false
        } else {
            controller?.play(); _isPlaying.value = true
        }
    }

    fun next() {
        val list = _currentList.value
        if (list.isEmpty()) return
        val nextIdx = if (_shuffleOn.value) randomIndexExcluding(_currentIndex.value, list.size)
        else { val n = _currentIndex.value + 1; if (n >= list.size) 0 else n }
        playAt(nextIdx)
    }

    fun previous() {
        val list = _currentList.value
        if (list.isEmpty()) return
        val prevIdx = if (_shuffleOn.value) randomIndexExcluding(_currentIndex.value, list.size)
        else { val p = _currentIndex.value - 1; if (p < 0) list.size - 1 else p }
        playAt(prevIdx)
    }

    private fun randomIndexExcluding(exclude: Int, size: Int): Int {
        if (size <= 1) return 0
        var r: Int
        do { r = (0 until size).random() } while (r == exclude)
        return r
    }

    fun toggleShuffle() { _shuffleOn.value = !_shuffleOn.value }
    fun toggleRepeatOne() { _repeatOneOn.value = !_repeatOneOn.value }

    fun onVideoEnded() {
        if (_repeatOneOn.value) playAt(_currentIndex.value) else next()
    }

    fun onExternalPause() { _isPlaying.value = false }
    fun onExternalPlay() { _isPlaying.value = true }

    fun pauseForBackground() {
        controller?.pause()
        _isPlaying.value = false
    }

    override fun onCleared() {
        super.onCleared()
        notificationManager.cancel()
    }

    private fun extractVideoId(url: String): String = try {
        val uri = Uri.parse(url)
        when {
            uri.host?.contains("youtu.be") == true -> uri.lastPathSegment ?: url
            uri.path?.contains("/shorts/") == true -> uri.lastPathSegment ?: url
            else -> uri.getQueryParameter("v") ?: url
        }
    } catch (e: Exception) { url }
}
