package com.compass.diary.ui.screens.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.ExportType
import com.compass.diary.viewmodel.ExportViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private fun millisToDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun dateToMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel()
) {
    val isExporting by viewModel.isExporting.collectAsState()
    val status by viewModel.status.collectAsState()
    val readyToSave by viewModel.readyToSave.collectAsState()

    var type by remember { mutableStateOf(ExportType.NOTES) }
    var fromDate by remember { mutableStateOf<LocalDate?>(null) }
    var toDate by remember { mutableStateOf<LocalDate?>(null) }
    var oneDay by remember { mutableStateOf(LocalDate.now()) }
    var sender by remember { mutableStateOf<String?>(null) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var showDayPicker by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) viewModel.writeZipTo(uri) else viewModel.cancelPendingSave() }

    LaunchedEffect(readyToSave) {
        if (readyToSave) {
            val name = viewModel.suggestedFileName(type, if (type == ExportType.ONE_DAY) oneDay else null)
            saveLauncher.launch(name)
        }
    }

    fun runExport() {
        when (type) {
            ExportType.NOTES  -> viewModel.exportNotes(fromDate, toDate)
            ExportType.SONGS  -> viewModel.exportSongs(fromDate, toDate, sender)
            ExportType.VOICE  -> viewModel.exportVoice(fromDate, toDate)
            ExportType.ONE_DAY -> viewModel.exportOneDay(oneDay)
            ExportType.FULL   -> viewModel.exportFull(fromDate, toDate)
        }
    }

    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Export Data", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(color = CompassColors.Gold400.copy(alpha = 0.15f), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = CompassColors.Gold400)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Every export is a password-protected ZIP", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Password: 0512", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text("What to export", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportOption(type == ExportType.NOTES, "Notes only", "All your written messages") { type = ExportType.NOTES }
                ExportOption(type == ExportType.SONGS, "Songs only", "All YouTube links shared") { type = ExportType.SONGS }
                ExportOption(type == ExportType.VOICE, "Voice only", "All voice messages + audio files") { type = ExportType.VOICE }
                ExportOption(type == ExportType.ONE_DAY, "One day", "Notes, songs, voice & both photos for a single date") { type = ExportType.ONE_DAY }
                ExportOption(type == ExportType.FULL, "Full backup", "Everything, organised by date") { type = ExportType.FULL }
            }

            HorizontalDivider()

            when (type) {
                ExportType.ONE_DAY -> {
                    Text("Choose date", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    AssistChip(
                        onClick = { showDayPicker = true },
                        label = { Text(oneDay.format(dateFmt)) },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) }
                    )
                }
                else -> {
                    Text("Date range (leave blank for all time)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { showFromPicker = true },
                            label = { Text(fromDate?.format(dateFmt) ?: "From: any") },
                            leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) }
                        )
                        AssistChip(
                            onClick = { showToPicker = true },
                            label = { Text(toDate?.format(dateFmt) ?: "To: any") },
                            leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) }
                        )
                        if (fromDate != null || toDate != null) {
                            TextButton(onClick = { fromDate = null; toDate = null }) { Text("Clear") }
                        }
                    }
                }
            }

            if (type == ExportType.SONGS) {
                Spacer(Modifier.height(4.dp))
                Text("Sender", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(sender == null, { sender = null }, { Text("All") })
                    FilterChip(sender == "JENMASANI", { sender = "JENMASANI" }, { Text("Jenmasani") })
                    FilterChip(sender == "KUTTY_GOLU", { sender = "KUTTY_GOLU" }, { Text("Kutty Golu") })
                }
            }

            Spacer(Modifier.height(8.dp))

            if (status != null) {
                Text(status ?: "", style = MaterialTheme.typography.bodyMedium,
                    color = if (status?.startsWith("Saved") == true) CompassColors.Success else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = { runExport() },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)
            ) {
                if (isExporting) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Export")
                }
            }
        }
    }

    if (showFromPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = fromDate?.let { dateToMillis(it) })
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { fromDate = millisToDate(it) }; showFromPicker = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showFromPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (showToPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toDate?.let { dateToMillis(it) })
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { toDate = millisToDate(it) }; showToPicker = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (showDayPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dateToMillis(oneDay))
        DatePickerDialog(
            onDismissRequest = { showDayPicker = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { oneDay = millisToDate(it) }; showDayPicker = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showDayPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun ExportOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) CompassColors.Blue600.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = CompassColors.Blue600))
            Spacer(Modifier.width(4.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
