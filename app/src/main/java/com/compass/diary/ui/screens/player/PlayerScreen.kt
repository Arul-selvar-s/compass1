package com.compass.diary.ui.screens.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.util.PlayerActionBus
import com.compass.diary.util.PlayerNotificationManager
import com.compass.diary.viewmodel.PlayerCategory
import com.compass.diary.viewmodel.PlayerViewModel
import com.compass.diary.viewmodel.YoutubePlayerController
import kotlinx.coroutines.launch

private const val PLAYER_HTML = """
<!DOCTYPE html><html><head><style>
html,body{margin:0;padding:0;background:#000;overflow:hidden;}
#player{position:absolute;top:0;left:0;width:100%;height:100%;}
</style></head>
<body>
<div id="player"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    height: '100%', width: '100%', videoId: '',
    playerVars: { playsinline: 1, rel: 0, modestbranding: 1, autoplay: 1 },
    events: {
      'onReady': function(e){ AndroidBridge.onReady(); },
      'onStateChange': function(e){ AndroidBridge.onStateChange(e.data); }
    }
  });
}
function loadVideo(id) { if (player && player.loadVideoById) player.loadVideoById(id); }
function playVideo() { if (player && player.playVideo) player.playVideo(); }
function pauseVideo() { if (player && player.pauseVideo) player.pauseVideo(); }
</script>
</body></html>
"""

private fun buildPlayerWebView(context: Context, viewModel: PlayerViewModel): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        addJavascriptInterface(object {
            @JavascriptInterface
            fun onReady() {
                post {
                    viewModel.controller = object : YoutubePlayerController {
                        override fun loadAndPlay(videoId: String) { evaluateJavascript("loadVideo('$videoId');", null) }
                        override fun play() { evaluateJavascript("playVideo();", null) }
                        override fun pause() { evaluateJavascript("pauseVideo();", null) }
                    }
                    val idx = viewModel.currentIndex.value
                    if (idx >= 0) viewModel.playAt(idx)
                }
            }
            @JavascriptInterface
            fun onStateChange(state: Int) {
                post {
                    when (state) {
                        1 -> viewModel.onExternalPlay()
                        2 -> viewModel.onExternalPause()
                        0 -> viewModel.onVideoEnded()
                    }
                }
            }
        }, "AndroidBridge")
        loadDataWithBaseURL("https://www.youtube.com", PLAYER_HTML, "text/html", "utf-8", null)
    }
}

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val category by viewModel.category.collectAsState()
    val currentList by viewModel.currentList.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val shuffleOn by viewModel.shuffleOn.collectAsState()
    val repeatOneOn by viewModel.repeatOneOn.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var showFullscreen by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val webView = remember { buildPlayerWebView(context, viewModel) }

    LaunchedEffect(category) { if (currentList.isEmpty()) viewModel.selectCategory(category) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.pauseForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(PlayerNotificationManager.ACTION_PLAY_PAUSE)
            addAction(PlayerNotificationManager.ACTION_NEXT)
            addAction(PlayerNotificationManager.ACTION_PREVIOUS)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                scope.launch { intent?.action?.let { PlayerActionBus.emit(it) } }
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        PlayerActionBus.actions.collect { action ->
            when (action) {
                PlayerNotificationManager.ACTION_PLAY_PAUSE -> viewModel.togglePlayPause()
                PlayerNotificationManager.ACTION_NEXT -> viewModel.next()
                PlayerNotificationManager.ACTION_PREVIOUS -> viewModel.previous()
            }
        }
    }

    if (showFullscreen && currentSong != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { webView })
            IconButton(onClick = { showFullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Default.FullscreenExit, "Exit fullscreen", tint = Color.White)
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    if (showSearch) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search song title or note…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Player", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) viewModel.clearSearch()
                    }) {
                        Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            if (showSearch) {
                if (searchQuery.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Type to search within “${categoryLabel(category)}”",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (searchResults.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matches", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(searchResults, key = { it.id }) { song ->
                            SearchResultRow(song) {
                                viewModel.playSearchResult(song)
                                showSearch = false
                            }
                        }
                    }
                }
                return@Column
            }

            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(category == PlayerCategory.ALL, { viewModel.selectCategory(PlayerCategory.ALL) }, { Text("All") }, modifier = Modifier.weight(1f))
                FilterChip(category == PlayerCategory.JENMASANI, { viewModel.selectCategory(PlayerCategory.JENMASANI) }, { Text("Jenmasani") }, modifier = Modifier.weight(1f))
                FilterChip(category == PlayerCategory.KUTTY_GOLU, { viewModel.selectCategory(PlayerCategory.KUTTY_GOLU) }, { Text("Kutty Golu") }, modifier = Modifier.weight(1f))
            }

            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
                    .clickable(enabled = currentSong != null) { showFullscreen = true }
            ) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { webView })
                if (currentSong != null) {
                    IconButton(onClick = { showFullscreen = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                        Icon(Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }
            }

            if (currentSong == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No songs in this category yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val song = currentSong!!
                val sender = if (song.sender == "JENMASANI") "Jenmasani" else "Kutty Golu"

                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (!song.title.isNullOrBlank()) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text("Sent by $sender", style = MaterialTheme.typography.labelMedium,
                        color = if (song.sender == "JENMASANI") CompassColors.Gold400 else CompassColors.Blue400)
                    if (!song.note.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(song.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleShuffle() }) {
                        Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleOn) CompassColors.Blue600 else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.previous() }) {
                        Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(32.dp))
                    }
                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(64.dp).background(CompassColors.Blue600, RoundedCornerShape(32.dp))
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause",
                            tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { viewModel.next() }) {
                        Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { viewModel.toggleRepeatOne() }) {
                        Icon(Icons.Default.RepeatOne, "Repeat one", tint = if (repeatOneOn) CompassColors.Blue600 else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "Volume is controlled by your phone's media volume buttons",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun categoryLabel(cat: PlayerCategory) = when (cat) {
    PlayerCategory.ALL -> "All"
    PlayerCategory.JENMASANI -> "Jenmasani"
    PlayerCategory.KUTTY_GOLU -> "Kutty Golu"
}

@Composable
private fun SearchResultRow(song: SongMessageEntity, onClick: () -> Unit) {
    val sender = if (song.sender == "JENMASANI") "Jenmasani" else "Kutty Golu"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayCircle, null, Modifier.size(24.dp), tint = Color(0xFFFF0000))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title ?: "Untitled video",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    "Sent by $sender" + if (!song.note.isNullOrBlank()) " • ${song.note}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
