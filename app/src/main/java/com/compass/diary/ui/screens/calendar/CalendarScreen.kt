package com.compass.diary.ui.screens.calendar

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.data.local.entity.PhotoEntity
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.DiaryViewModel
import com.compass.diary.viewmodel.PhotoViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    onPage: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: DiaryViewModel = hiltViewModel(),
    photoViewModel: PhotoViewModel = hiltViewModel()
) {
    val allKeys by viewModel.allDateKeys.collectAsState()
    val keySet  = remember(allKeys) { allKeys.toSet() }
    val allPhotos by photoViewModel.allPhotos.collectAsState()
    val latestPhotoByDate = remember(allPhotos) {
        allPhotos.groupBy { it.dateKey }.mapValues { (_, v) -> v.maxByOrNull { it.takenAt } }
    }
    var month   by remember { mutableStateOf(YearMonth.now()) }
    val today   = remember { LocalDate.now() }
    var fullPhoto by remember { mutableStateOf<PhotoEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Calendar", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "Prev") }
                Text("${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) {
                    Icon(Icons.Default.ChevronRight, "Next")
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth()) {
                listOf("Mo","Tu","We","Th","Fr","Sa","Su").forEach { d ->
                    Text(d, Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))

            val first  = month.atDay(1)
            val offset = first.dayOfWeek.value - 1
            val days   = month.lengthOfMonth()
            val rows   = (offset + days + 6) / 7

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(rows) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { col ->
                            val idx = row * 7 + col
                            val day = idx - offset + 1
                            if (day < 1 || day > days) { Spacer(Modifier.weight(1f)) }
                            else {
                                val date    = month.atDay(day)
                                val key     = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                val isToday = date == today
                                val hasEntry = key in keySet
                                val future  = date > today
                                val photo   = latestPhotoByDate[key]
                                val bmp = remember(photo?.fileName) {
                                    photo?.let { runCatching { BitmapFactory.decodeFile(photoViewModel.photoFile(it.fileName).absolutePath) }.getOrNull() }
                                }
                                Box(
                                    Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                        .background(when { isToday -> CompassColors.Gold400; hasEntry -> CompassColors.Blue500; else -> Color.Transparent }, CircleShape)
                                        .then(
                                            if (!future) Modifier.combinedClickable(
                                                onClick = { onPage(key) },
                                                onLongClick = { if (photo != null) fullPhoto = photo }
                                            ) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bmp != null) {
                                        androidx.compose.foundation.Image(
                                            bmp.asImageBitmap(), null,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f), CircleShape))
                                    }
                                    Text("$day", style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isToday || hasEntry) FontWeight.Bold else FontWeight.Normal,
                                        color = when { bmp != null -> Color.White; future -> MaterialTheme.colorScheme.onSurface.copy(.3f); isToday || hasEntry -> Color.White; else -> MaterialTheme.colorScheme.onSurface })
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).background(CompassColors.Blue500, CircleShape)); Spacer(Modifier.width(6.dp)); Text("Has entry", style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).background(CompassColors.Gold400, CircleShape)); Spacer(Modifier.width(6.dp)); Text("Today", style = MaterialTheme.typography.labelSmall) }
            }
            Text("Hold a date's photo to view it full size", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(16.dp))

            val prefix = month.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            val count  = keySet.count { it.startsWith(prefix) }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), Arrangement.SpaceEvenly) {
                    StatCol("$count", "Entries")
                    VerticalDivider(Modifier.height(40.dp))
                    StatCol("${month.lengthOfMonth() - count}", "Missed")
                    VerticalDivider(Modifier.height(40.dp))
                    StatCol("${if (month.lengthOfMonth() > 0) count * 100 / month.lengthOfMonth() else 0}%", "Consistency")
                }
            }
        }
    }

    if (fullPhoto != null) {
        val file = photoViewModel.photoFile(fullPhoto!!.fileName)
        Dialog(onDismissRequest = { fullPhoto = null }) {
            val bmp = remember(fullPhoto!!.fileName) { runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
            if (bmp != null) {
                androidx.compose.foundation.Image(bmp.asImageBitmap(), "Photo",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable private fun StatCol(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
