package com.compass.diary.ui.screens.editor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.data.local.entity.NoteMessageEntity
import com.compass.diary.data.local.entity.PhotoEntity
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.local.entity.VoiceMessageEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.DiaryViewModel
import com.compass.diary.viewmodel.PhotoViewModel
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
    voiceViewModel: VoiceViewModel = hiltViewModel(),
    photoViewModel: PhotoViewModel = hiltViewModel()
) {
    val entry     by viewModel.currentEntry.collectAsState()
    val todayKey  by viewModel.todayKey.collectAsState()
    val messages  by remember(dateKey) { viewModel.notesForDate(dateKey) }.collectAsState(initial = emptyList())
    val allSongs  by songViewModel.songs.collectAsState()
    val allVoice  by voiceViewModel.messages.collectAsState()
    val playingId by voiceViewModel.playingId.collectAsState()
    val dayPhotos by remember(dateKey) { photoViewModel.photosForDate(dateKey) }.collectAsState(initial = emptyList())

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    var input by remember { mutableStateOf("") }
    val isToday = dateKey == todayKey
    val latestPhoto = dayPhotos.maxByOrNull { it.takenAt }

    var showCapturePreview by remember { mutableStateOf(false) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var showFullPhoto by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) showCapturePreview = true else photoViewModel.discardCapture()
    }
    fun launchCamera() {
        val uri = photoViewModel.createCaptureUri()
        pendingCaptureUri = uri
        cameraLauncher.launch(uri)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCamera() }

    fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (latestPhoto != null) {
                            val file = remember(latestPhoto.fileName) { photoViewModel.photoFile(latestPhoto.fileName) }
                            val bmp = remember(latestPhoto.fileName) { runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
                            if (bmp != null) {
                                Image(
                                    bmp.asImageBitmap(), "Today's photo",
                                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { showFullPhoto = true },
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                        }
                        Column {
                            Text(entry?.title ?: dateKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${entry?.wordCount ?: 0} words", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (isToday) {
                        IconButton(onClick = { if (dayPhotos.size < 2) requestCamera() }, enabled = dayPhotos.size < 2) {
                            Icon(Icons.Default.CameraAlt, "Take photo",
                                tint = if (dayPhotos.size < 2) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
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

    if (showCapturePreview) {
        val file = photoViewModel.currentCaptureFile()
        AlertDialog(
            onDismissRequest = { /* force an explicit choice */ },
            title = { Text("Save this photo?") },
            text = {
                if (file != null) {
                    val bmp = remember(file) { runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    photoViewModel.savePendingCapture(dateKey)
                    showCapturePreview = false
                }, colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    photoViewModel.discardCapture()
                    showCapturePreview = false
                    launchCamera()
                }) { Text("Retake") }
            }
        )
    }

    if (showFullPhoto && latestPhoto != null) {
        val file = photoViewModel.photoFile(latestPhoto.fileName)
        Dialog(onDismissRequest = { showFullPhoto = false }) {
            val bmp = remember(latestPhoto.fileName) { runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), "Photo", Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
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
