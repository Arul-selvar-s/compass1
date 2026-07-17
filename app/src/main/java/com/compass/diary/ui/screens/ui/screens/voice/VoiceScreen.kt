package com.compass.diary.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.data.local.entity.VoiceMessageEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.VoiceViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    viewModel: VoiceViewModel = hiltViewModel()
) {
    val messages    by viewModel.messages.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val playingId   by viewModel.playingId.collectAsState()
    val context     = LocalContext.current
    val timeFmt     = remember { SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()) }

    var pendingNote by remember { mutableStateOf("") }
    var showRecordSheet by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { showRecordSheet = true; viewModel.startRecording() } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) { pendingImportUri = uri; showImportSheet = true }
    }

    fun requestRecord() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) { showRecordSheet = true; viewModel.startRecording() }
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Voice Messages", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.UploadFile, "Import audio")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { requestRecord() }, containerColor = CompassColors.Blue600) {
                Icon(Icons.Default.Mic, "Record", tint = Color.White)
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (isProcessing) {
                Row(
                    Modifier.fillMaxWidth().background(CompassColors.Blue800).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Compressing audio…", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Mic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("No voice messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tap the mic to record, or import an audio file",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        VoiceCard(msg, isPlaying = playingId == msg.id, timeFmt = timeFmt) {
                            viewModel.togglePlay(msg)
                        }
                    }
                }
            }
        }
    }

    if (showRecordSheet) {
        ModalBottomSheet(onDismissRequest = { viewModel.cancelRecording(); showRecordSheet = false; pendingNote = "" }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, null, Modifier.size(48.dp), tint = CompassColors.Error)
                Spacer(Modifier.height(12.dp))
                Text(if (isRecording) "Recording…" else "Ready", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = pendingNote,
                    onValueChange = { pendingNote = it },
                    placeholder = { Text("Add a note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.cancelRecording(); showRecordSheet = false; pendingNote = "" },
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }
                    Button(
                        onClick = { viewModel.stopRecordingAndSave(pendingNote); showRecordSheet = false; pendingNote = "" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)
                    ) { Text("Stop & Save") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false; pendingNote = ""; pendingImportUri = null }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.UploadFile, null, Modifier.size(48.dp), tint = CompassColors.Blue400)
                Spacer(Modifier.height(12.dp))
                Text("Import this audio file", style = MaterialTheme.typography.titleMedium)
                Text("It will be compressed automatically", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = pendingNote,
                    onValueChange = { pendingNote = it },
                    placeholder = { Text("Add a note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        pendingImportUri?.let { viewModel.importAudio(it, pendingNote) }
                        showImportSheet = false; pendingNote = ""; pendingImportUri = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)
                ) { Text("Import & Compress") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun VoiceCard(msg: VoiceMessageEntity, isPlaying: Boolean, timeFmt: SimpleDateFormat, onToggle: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(CompassColors.Blue600, CircleShape)) {
                IconButton(onClick = onToggle, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Play", tint = Color.White
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(msg.durationMs), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Surface(color = if (msg.sourceType == "RECORDED") CompassColors.Success.copy(alpha = 0.15f) else CompassColors.Blue400.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)) {
                        Text(
                            if (msg.sourceType == "RECORDED") "Recorded" else "Imported",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.sourceType == "RECORDED") CompassColors.Success else CompassColors.Blue400,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!msg.note.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(msg.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(timeFmt.format(Date(msg.sentAt)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
