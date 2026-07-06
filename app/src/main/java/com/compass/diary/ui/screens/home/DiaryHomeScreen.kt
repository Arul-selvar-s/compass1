package com.compass.diary.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.data.local.entity.DiaryEntryEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.DiaryViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryHomeScreen(
    onPage: (String) -> Unit,
    onCalendar: () -> Unit,
    onStarred: () -> Unit,
    onSearch: () -> Unit,
    onAI: () -> Unit,
    onReminders: () -> Unit,
    onSettings: () -> Unit,
    viewModel: DiaryViewModel = hiltViewModel()
) {
    val entries  by viewModel.allEntries.collectAsState()
    val todayKey by viewModel.todayKey.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Compass", fontWeight = FontWeight.Bold)
                        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onPage(todayKey) },
                icon = { Icon(Icons.Default.Edit, null) },
                text = { Text("Today") },
                containerColor = CompassColors.Blue600,
                contentColor = Color.White
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(
                    Triple(Icons.Default.CalendarMonth, "Calendar", onCalendar),
                    Triple(Icons.Default.Star, "Starred", onStarred),
                    Triple(Icons.Default.AutoAwesome, "AI", onAI),
                    Triple(Icons.Default.Notifications, "Reminders", onReminders)
                ).forEach { (icon, label, action) ->
                    NavigationBarItem(icon = { Icon(icon, label) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        selected = false, onClick = action)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val wc by viewModel.totalWordCount.collectAsState()
                    StatCard("Pages", "${entries.size}", Modifier.weight(1f))
                    StatCard("Words", "$wc", Modifier.weight(1f))
                    StatCard("Streak", "${viewModel.streakDays}d", Modifier.weight(1f))
                }
            }
            item {
                TodayCard(entries.find { it.dateKey == todayKey }) { onPage(todayKey) }
            }
            if (entries.any { it.dateKey != todayKey }) {
                item {
                    Text("Past Pages", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
                items(entries.filter { it.dateKey != todayKey }, key = { it.dateKey }) { e ->
                    EntryCard(e) { onPage(e.dateKey) }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TodayCard(entry: DiaryEntryEntity?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CompassColors.Blue800)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = CompassColors.Blue300)
                Text(if (entry?.plainText?.isNotBlank() == true) entry.title else "Start writing…",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (entry?.plainText?.isNotBlank() == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(entry.plainText.take(100), style = MaterialTheme.typography.bodySmall,
                        color = CompassColors.Blue300, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(if (entry?.plainText?.isNotBlank() == true) Icons.Default.Edit else Icons.Default.AddCircleOutline,
                null, tint = CompassColors.Blue300, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun EntryCard(entry: DiaryEntryEntity, onClick: () -> Unit) {
    val date = runCatching { java.time.LocalDate.parse(entry.dateKey).format(DateTimeFormatter.ofPattern("dd MMM")) }.getOrDefault(entry.dateKey)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                Text(date, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = CompassColors.Blue400)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (entry.plainText.isNotBlank())
                    Text(entry.plainText.take(80), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${entry.wordCount} words", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
