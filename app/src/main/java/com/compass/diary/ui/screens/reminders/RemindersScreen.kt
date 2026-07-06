package com.compass.diary.ui.screens.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.data.local.entity.ReminderEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.DiaryViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(onBack: () -> Unit, viewModel: DiaryViewModel = hiltViewModel()) {
    val upcoming  by viewModel.upcomingReminders.collectAsState()
    val completed by viewModel.completedReminders.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAdd     by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Reminders", fontWeight = FontWeight.Bold) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }, containerColor = CompassColors.Blue600) {
                Icon(Icons.Default.Add, "Add", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("Upcoming (${upcoming.size})") })
                Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("Completed") })
            }
            val list = if (selectedTab == 0) upcoming else completed
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔔", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(if (selectedTab == 0) "No upcoming reminders" else "No completed reminders",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val df = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = CompassColors.Blue400)
                                        Spacer(Modifier.width(4.dp))
                                        Text(df.format(Date(r.triggerAt)), style = MaterialTheme.typography.labelSmall, color = CompassColors.Blue400)
                                        if (r.repeatType != "ONCE") {
                                            Spacer(Modifier.width(8.dp))
                                            AssistChip({}, { Text(r.repeatType.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(20.dp))
                                        }
                                    }
                                }
                                if (!r.isCompleted) IconButton(onClick = { viewModel.markReminderComplete(r.id) }) { Icon(Icons.Default.CheckCircleOutline, "Complete") }
                                IconButton(onClick = { viewModel.deleteReminder(r) }) { Icon(Icons.Default.Delete, "Delete", tint = CompassColors.Error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddReminderSheet(onAdd = { viewModel.addReminder(it); showAdd = false }, onDismiss = { showAdd = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderSheet(onAdd: (ReminderEntity) -> Unit, onDismiss: () -> Unit) {
    var title  by remember { mutableStateOf("") }
    var note   by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("ONCE") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("New Reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(note,  { note  = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            Text("Repeat", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ONCE","DAILY","WEEKLY","MONTHLY").forEach { t ->
                    FilterChip(repeat == t, { repeat = t }, { Text(t.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (title.isNotBlank()) onAdd(ReminderEntity(title = title, note = note, repeatType = repeat, triggerAt = System.currentTimeMillis() + 3_600_000L)) },
                Modifier.fillMaxWidth().height(52.dp), enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)
            ) { Text("Save Reminder") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
