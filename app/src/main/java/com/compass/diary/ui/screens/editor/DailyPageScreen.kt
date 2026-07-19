package com.compass.diary.ui.screens.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.data.local.entity.NoteMessageEntity
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.local.entity.VoiceMessageEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.DiaryViewModel
import com.compass.diary.viewmodel.SongViewModel
import com.compass.diary.viewmodel.VoiceViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

private fun localDateOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun openYoutube(context: Context, url: String) {
    try {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage("com.google.android.youtube") }
        context.startActivity(appIntent)
    } catch (e: Exception) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyPageScreen(
    dateKey: String,
    onBack: () -> Unit,
    onAI: () -> Unit,
    viewModel: DiaryViewModel = hiltViewModel(),
    songViewModel: SongViewModel = hiltViewModel(),
    voiceViewModel: VoiceViewModel = hiltViewModel()
) {
    val entry     by viewModel.currentEntry.collectAsState()
    val messages  by remember(dateKey) { viewModel.notesForDate(dateKey) }.collectAsState(initial = emptyList())
    val allSongs  by songViewModel.songs.collectAsState()
    val allVoice  by voiceViewModel.messages.collectAsState()
    val playingId by voiceViewModel.playingId.collectAsState()

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    var input by remember { mutableStateOf("") }

    val dayDate = remember(dateKey) { runCatching { LocalDate.parse(dateKey) }.getOrNull() }
    val songsToday = remember(allSongs, dateKey) { allSongs.filter { dayDate != null && localDateOf(it.sentAt) == dayDate } }
    val voiceToday = remember(allVoice, dateKey) { allVoice.filter { dayDate != null && localDateOf(it.sentAt) == dayDate } }

    LaunchedEffect(dateKey) { viewModel.selectEntry(dateKey) }
    LaunchedEffect(messages.size, songsToday.size, voiceToday.size) {
        scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank()) return
        viewModel.sendNote(dateKey, text)
        input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(entry?.title ?: dateKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${entry?.wordCount ?: 0} words", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.starWholeDay(dateKey) }) {
                        Icon(Icons.Default.Star, "Star this day", tint = CompassColors.Star)
                    }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Write something…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { send() },
                    enabled = input.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .background(if (input.isNotBlank()) CompassColors.Blue600 else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (input.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (voiceToday.isNotEmpty() || songsToday.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (songsToday.isNotEmpty()) {
                            Text("Songs from this day", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            songsToday.forEach { s -> DaySongRow(s, timeFmt) { openYoutube(context, s.youtubeUrl) } }
                        }
                        if (voiceToday.isNotEmpty()) {
                            Text("Voice messages from this day", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            voiceToday.forEach { v -> DayVoiceRow(v, playingId == v.id, timeFmt) { voiceViewModel.togglePlay(v) } }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            if (messages.isEmpty() && songsToday.isEmpty() && voiceToday.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Nothing written yet — say something below",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(messages.reversed(), key = { it.id }) { msg ->
                NoteBubble(msg, timeFmt) { viewModel.starNoteMessage(dateKey, msg.text) }
            }
        }
    }
}

@Composable
private fun NoteBubble(msg: NoteMessageEntity, timeFmt: SimpleDateFormat, onStar: () -> Unit) {
    var starred by remember(msg.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(msg.text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(timeFmt.format(Date(msg.sentAt)), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            IconButton(onClick = { onStar(); starred = true }, Modifier.size(24.dp)) {
                Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder, "Star",
                    tint = if (starred) CompassColors.Star else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun DaySongRow(song: SongMessageEntity, timeFmt: SimpleDateFormat, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayCircle, null, Modifier.size(22.dp), tint = Color(0xFFFF0000))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (song.sender == "JENMASANI") "Jenmasani" else "Kutty Golu", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                if (!song.note.isNullOrBlank()) Text(song.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(timeFmt.format(Date(song.sentAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayVoiceRow(msg: VoiceMessageEntity, isPlaying: Boolean, timeFmt: SimpleDateFormat, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, null, Modifier.size(20.dp), tint = CompassColors.Blue400)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isPlaying) "Playing…" else "Tap to play", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                if (!msg.note.isNullOrBlank()) Text(msg.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(timeFmt.format(Date(msg.sentAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
