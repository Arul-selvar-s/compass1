package com.compass.diary.ui.screens.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.util.PlayerActionBus
import com.compass.diary.util.PlayerNotificationManager
import com.compass.diary.viewmodel.PlayerCategory
import com.compass.diary.viewmodel.PlayerViewModel
import com.compass.diary.viewmodel.YoutubePlayerController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun playerHtml(origin: String): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>
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
    height: '100%', width: '100%',
    playerVars: { playsinline: 1, rel: 0, modestbranding: 1, autoplay: 1, origin: '$origin' },
    events: {
      'onReady': function(e){ AndroidBridge.onReady(); },
      'onStateChange': function(e){ AndroidBridge.onStateChange(e.data); },
      'onError': function(e){ AndroidBridge.onError(e.data); }
    }
  });
}
function loadVideo(id) { if (player && player.loadVideoById) player.loadVideoById(id); }
function playVideo() { if (player && player.playVideo) player.playVideo(); }
function pauseVideo() { if (player && player.pauseVideo) player.pauseVideo(); }
function seekToSec(s) { if (player && player.seekTo) player.seekTo(s, true); }
setInterval(function() {
  if (player && player.getCurrentTime && player.getDuration) {
    AndroidBridge.onProgress(player.getCurrentTime(), player.getDuration());
  }
}, 500);
</script>
</body></html>
"""

private fun buildPlayerWebView(context: Context, viewModel: PlayerViewModel): WebView {
    // Identify as the app's own domain (YouTube checks the embed's origin/referrer).
    val origin = "https://${context.packageName}"
    return WebView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
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
                        override fun seekTo(seconds: Float) { evaluateJavascript("seekToSec($seconds);", null) }
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
            @JavascriptInterface
            fun onError(code: Int) {
                post { viewModel.onPlayerError(code) }
            }
            @JavascriptInterface
            fun onProgress(current: Double, duration: Double) {
                viewModel.onProgress(current, duration)
            }
        }, "AndroidBridge")
        loadDataWithBaseURL(origin, playerHtml(origin), "text/html", "utf-8", null)
    }
}

private fun openInYoutube(context: Context, url: String) {
    try {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage("com.google.android.youtube") }
        context.startActivity(appIntent)
    } catch (e: Exception) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }
}

private fun videoIdOf(url: String): String = try {
    val uri = Uri.parse(url)
    when {
        uri.host?.contains("youtu.be") == true -> uri.lastPathSegment ?: url
        uri.path?.contains("/shorts/") == true -> uri.lastPathSegment ?: url
        else -> uri.getQueryParameter("v") ?: url
    }
} catch (e: Exception) { url }

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
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
    val playerError by viewModel.playerError.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }

    var showFullscreen by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(false) }   // false = poster + audio only
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val webView = remember { buildPlayerWebView(context, viewModel) }

    LaunchedEffect(Unit) { if (currentList.isEmpty()) viewModel.selectCategory(category) }

    DisposableEffect(webView) {
        onDispose {
            viewModel.controller = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

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

    // Reuse the SAME WebView instance in both places; detach from any old parent first.
    val webViewFactory: (Context) -> WebView = {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView
    }

    if (showFullscreen && currentSong != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = webViewFactory)
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

            // Player area: the WebView always sits at the bottom (so audio keeps playing);
            // in poster mode an opaque thumbnail covers it.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = webViewFactory)

                val song0 = currentSong
                if (!showVideo && song0 != null) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black)
                            .pointerInput(Unit) { detectTapGestures { } }
                    ) {
                        AsyncImage(
                            model = "https://img.youtube.com/vi/${videoIdOf(song0.youtubeUrl)}/hqdefault.jpg",
                            contentDescription = "Song poster",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (song0 != null) {
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { showVideo = !showVideo },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                if (showVideo) Icons.Default.Image else Icons.Default.Videocam,
                                null, Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (showVideo) "Poster" else "Video", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (showVideo) {
                        IconButton(onClick = { showFullscreen = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                            Icon(Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                        }
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

                if (playerError != null) {
                    Surface(
                        color = CompassColors.Error.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "YouTube couldn't play this here (error ${playerError}). " +
                                    "If only some songs show this, the uploader has blocked embedding for that video.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CompassColors.Error
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { openInYoutube(context, song.youtubeUrl) }) { Text("Open in YouTube") }
                                OutlinedButton(onClick = { viewModel.next() }) { Text("Skip") }
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (!song.title.isNullOrBlank()) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text("Sent by $sender", style = MaterialTheme.typography.labelMedium,
                        color = if (song.sender == "JENMASANI") CompassColors.Gold400 else CompassColors.Blue400)
                    Text(dateFmt.format(Date(song.sentAt)), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!song.note.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(song.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Seek bar
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val duration = durationMs.coerceAtLeast(1L).toFloat()
                    val shown = (dragValue ?: positionMs.toFloat()).coerceIn(0f, duration)
                    Slider(
                        value = shown,
                        onValueChange = { dragValue = it },
                        onValueChangeFinished = {
                            dragValue?.let { viewModel.seekToMs(it.toLong()) }
                            dragValue = null
                        },
                        valueRange = 0f..duration,
                        enabled = durationMs > 0
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
