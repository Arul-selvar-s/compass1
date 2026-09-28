package com.compass.diary.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.util.PlayerState
import com.compass.diary.util.PlayerStateStore
import com.compass.diary.util.YoutubeMetadataFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PlayerCategory { ALL, JENMASANI, KUTTY_GOLU }

interface YoutubePlayerController {
    fun loadAndPlay(videoId: String, startSeconds: Float)
    fun play()
    fun pause()
    fun seekTo(seconds: Float)
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repo: DiaryRepository,
    private val stateStore: PlayerStateStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Set when a song was tapped in the Songs screen; -1 = just open the Player. */
    private val requestedSongId: Long = savedStateHandle.get<Long>("songId") ?: -1L

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

    private val _playerError = MutableStateFlow<Int?>(null)
    val playerError: StateFlow<Int?> = _playerError

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

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
        val idx = _currentList.value.indexOfFirst { it.id == song.id }
        if (idx >= 0) playAt(idx)
        clearSearch()
    }

    var controller: YoutubePlayerController? = null

    private var resumeState: PlayerState? = null
    private var initialResolved = false
    private var pendingStartSec = 0f

    init {
        viewModelScope.launch {
            // Where did we (or the other phone) leave off? Skipped if a specific song was tapped.
            if (requestedSongId < 0) resumeState = stateStore.loadBest()
            val songs = repo.getAllSongs().first()
            _currentList.value = filteredSorted(_category.value, songs)
            startInitial()
        }
        viewModelScope.launch {
            allSongs.collect { songs ->
                if (initialResolved) refreshList(songs)   // new songs join the queue right away
                backfillMissingTitles(songs)
            }
        }
        // Save position locally every 5s while playing; upload to Drive every 15s.
        viewModelScope.launch {
            var ticks = 0
            while (true) {
                delay(5000)
                if (_isPlaying.value) {
                    ticks++
                    persistNow(upload = ticks % 3 == 0)
                }
            }
        }
    }

    private fun songKey(song: SongMessageEntity) = "${song.sentAt}|${song.youtubeUrl}"

    private fun startInitial() {
        val list = _currentList.value
        if (list.isNotEmpty()) {
            val requestedIdx = if (requestedSongId >= 0) list.indexOfFirst { it.id == requestedSongId } else -1
            val resume = resumeState
            val resumeIdx = if (resume != null) list.indexOfFirst { songKey(it) == resume.songKey } else -1
            when {
                requestedIdx >= 0 -> playAt(requestedIdx)
                resume != null && resumeIdx >= 0 -> {
                    val nearEnd = resume.durationMs > 0 && resume.positionMs > resume.durationMs - 5000
                    val startMs = if (nearEnd) 0L else resume.positionMs
                    playAt(resumeIdx, startMs / 1000f)
                }
                else -> playAt(list.size - 1)
            }
        }
        resumeState = null
        initialResolved = true
        _loading.value = false
    }

    private val titleFetchAttempted = mutableSetOf<Long>()

    private fun backfillMissingTitles(songs: List<SongMessageEntity>) {
        songs.filter { it.title.isNullOrBlank() && titleFetchAttempted.add(it.id) }.forEach { song ->
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
        } else if (_currentIndex.value == -1 && filtered.isNotEmpty()) {
            playAt(filtered.size - 1)
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

    fun playAt(index: Int, startSec: Float = 0f) {
        val list = _currentList.value
        if (index !in list.indices) return
        _playerError.value = null
        pendingStartSec = startSec
        _positionMs.value = (startSec * 1000).toLong()
        _durationMs.value = 0L
        _currentIndex.value = index
        _isPlaying.value = true
        controller?.loadAndPlay(extractVideoId(list[index].youtubeUrl), startSec)
        persistNow(upload = true)
    }

    /** Called once the YouTube player inside the WebView is ready. */
    fun onPlayerReady() {
        val list = _currentList.value
        val idx = _currentIndex.value
        if (idx in list.indices) {
            controller?.loadAndPlay(extractVideoId(list[idx].youtubeUrl), pendingStartSec)
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            controller?.pause(); _isPlaying.value = false
            persistNow(upload = true)
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

    fun onPlayerError(code: Int) {
        _playerError.value = code
        _isPlaying.value = false
    }

    fun onProgress(currentSec: Double, durationSec: Double) {
        if (currentSec.isNaN() || durationSec.isNaN() || durationSec <= 0.0) return
        _positionMs.value = (currentSec * 1000).toLong().coerceAtLeast(0L)
        _durationMs.value = (durationSec * 1000).toLong().coerceAtLeast(0L)
    }

    fun seekToMs(ms: Long) {
        _positionMs.value = ms
        controller?.seekTo(ms / 1000f)
    }

    fun onExternalPause() {
        _isPlaying.value = false
        persistNow(upload = true)
    }

    fun onExternalPlay() { _isPlaying.value = true; _playerError.value = null }

    fun pauseForBackground() {
        controller?.pause()
        _isPlaying.value = false
        persistNow(upload = true)
    }

    private fun persistNow(upload: Boolean) {
        if (!initialResolved) return
        val song = _currentList.value.getOrNull(_currentIndex.value) ?: return
        stateStore.save(
            PlayerState(
                songKey = songKey(song),
                positionMs = _positionMs.value,
                durationMs = _durationMs.value,
                updatedAt = System.currentTimeMillis()
            ),
            upload
        )
    }

    override fun onCleared() {
        persistNow(upload = true)
        controller = null
        super.onCleared()
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
