package com.compass.diary.ui.screens.songs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.SongViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val SENDER_JENMASANI = "JENMASANI"
private const val SENDER_KUTTY_GOLU = "KUTTY_GOLU"

private val YOUTUBE_REGEX = Regex(
    "(youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/)",
    RegexOption.IGNORE_CASE
)

private fun isValidYoutubeUrl(url: String) = YOUTUBE_REGEX.containsMatchIn(url)

private fun extractVideoId(url: String): String {
    return try {
        val uri = Uri.parse(url)
        when {
            uri.host?.contains("youtu.be") == true -> uri.lastPathSegment ?: url
            uri.path?.contains("/shorts/") == true -> uri.lastPathSegment ?: url
            else -> uri.getQueryParameter("v") ?: url
        }
    } catch (e: Exception) { url }
}

private fun openYoutube(context: Context, url: String) {
    try {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.google.android.youtube")
        }
        context.startActivity(appIntent)
    } catch (e: Exception) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(
    onBack: () -> Unit,
    viewModel: SongViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    var urlInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }

    LaunchedEffect(songs.size) {
        if (songs.isNotEmpty()) scope.launch { listState.animateScrollToItem(songs.size - 1) }
    }

    fun send(sender: String) {
        if (!isValidYoutubeUrl(urlInput)) return
        viewModel.sendSong(urlInput, noteInput, sender)
        urlInput = ""
        noteInput = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar("Y", CompassColors.Gold400)
                        Spacer(Modifier.width(4.dp))
                        Avatar("A", CompassColors.Blue400, overlap = true)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Songs", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Jenmasani & Kutty Golu", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("Paste a YouTube link…") },
                    singleLine = true,
                    isError = urlInput.isNotBlank() && !isValidYoutubeUrl(urlInput),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    placeholder = { Text("Add a note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { send(SENDER_JENMASANI) },
                        enabled = isValidYoutubeUrl(urlInput),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Gold400)
                    ) {
                        Icon(Icons.Default.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("Send as Jenmasani")
                    }
                    Button(
                        onClick = { send(SENDER_KUTTY_GOLU) },
                        enabled = isValidYoutubeUrl(urlInput),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue400)
                    ) {
                        Icon(Icons.Default.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("Send as Kutty Golu")
                    }
                }
            }
        }
    ) { padding ->
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("No songs shared yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    SongBubble(song, timeFmt) { openYoutube(context, song.youtubeUrl) }
                }
            }
        }
    }
}

@Composable
private fun Avatar(letter: String, color: Color, overlap: Boolean = false) {
    Box(
        Modifier.size(32.dp)
            .then(if (overlap) Modifier.padding(start = 0.dp) else Modifier)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun SongBubble(song: SongMessageEntity, timeFmt: SimpleDateFormat, onOpen: () -> Unit) {
    val isRight = song.sender == SENDER_JENMASANI
    val bubbleColor = if (isRight) CompassColors.Gold400.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val avatarLetter = if (isRight) "Y" else "A"
    val avatarColor = if (isRight) CompassColors.Gold400 else CompassColors.Blue400
    val senderLabel = if (isRight) "Jenmasani" else "Kutty Golu"

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isRight) Arrangement.End else Arrangement.Start
    ) {
        if (!isRight) { Avatar(avatarLetter, avatarColor); Spacer(Modifier.width(8.dp)) }

        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .clickable { onOpen() }
                .padding(12.dp)
        ) {
            Text(senderLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = if (isRight) CompassColors.Gold400 else CompassColors.Blue400)
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayCircle, null, Modifier.size(28.dp), tint = Color(0xFFFF0000))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("YouTube video", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("Tap to play  •  ${extractVideoId(song.youtubeUrl).take(16)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!song.note.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row {
                    Box(Modifier.width(3.dp).height(32.dp).background(avatarColor, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(song.note, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(timeFmt.format(Date(song.sentAt)), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.End))
        }

        if (isRight) { Spacer(Modifier.width(8.dp)); Avatar(avatarLetter, avatarColor) }
    }
}
