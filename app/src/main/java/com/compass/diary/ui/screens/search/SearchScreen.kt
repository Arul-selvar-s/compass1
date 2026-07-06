package com.compass.diary.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.DiaryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onPage: (String) -> Unit, onBack: () -> Unit, viewModel: DiaryViewModel = hiltViewModel()) {
    val query   by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val focus   = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    OutlinedTextField(value = query, onValueChange = viewModel::search,
                        placeholder = { Text("Search your diary…") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        shape = MaterialTheme.shapes.large,
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) })
                }
            )
        }
    ) { padding ->
        if (query.isBlank()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔍", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Search all diary pages", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text("No results for \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("${results.size} result${if (results.size != 1) "s" else ""} for \"$query\"", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(results, key = { it.dateKey }) { e ->
                    val idx     = e.plainText.indexOf(query, ignoreCase = true)
                    val preview = if (idx >= 0) { val s = (idx - 40).coerceAtLeast(0); val end = (idx + 80).coerceAtMost(e.plainText.length); (if (s > 0) "…" else "") + e.plainText.substring(s, end) } else e.plainText.take(100)
                    Card(onClick = { onPage(e.dateKey) }, Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text("${e.wordCount} words", style = MaterialTheme.typography.labelSmall, color = CompassColors.Blue400)
                        }
                    }
                }
            }
        }
    }
}
