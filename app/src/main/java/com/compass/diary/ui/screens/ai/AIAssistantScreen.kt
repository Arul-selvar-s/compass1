package com.compass.diary.ui.screens.ai

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.AIViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIAssistantScreen(onBack: () -> Unit, onPage: (String) -> Unit, viewModel: AIViewModel = hiltViewModel()) {
    val messages   by viewModel.messages.collectAsState()
    val thinking   by viewModel.isThinking.collectAsState()
    val keySet     by viewModel.isApiKeyConfigured.collectAsState()
    var input      by remember { mutableStateOf("") }
    val listState  = rememberLazyListState()

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✨", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("AI Assistant", fontWeight = FontWeight.Bold)
                            Text(if (keySet) "Ready" else "Add API key in Settings",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (keySet) CompassColors.Success else CompassColors.Warning)
                        }
                    }
                },
                actions = { IconButton(onClick = viewModel::clearConversation) { Icon(Icons.Default.ClearAll, "Clear") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (messages.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ask anything about your diary", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    listOf("Summarise this week", "What did I write about work?", "Find travel mentions", "What are my goals?").chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { s ->
                                SuggestionChip(onClick = { viewModel.ask(s) }, label = { Text(s, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(messages, key = { it.id }) { msg ->
                    val isUser = msg.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                        if (!isUser) { Box(Modifier.size(32.dp).background(CompassColors.Blue600, RoundedCornerShape(8.dp)), Alignment.Center) { Text("✨", style = MaterialTheme.typography.labelLarge) }; Spacer(Modifier.width(8.dp)) }
                        Column(Modifier.widthIn(max = 280.dp)) {
                            Surface(color = if (isUser) CompassColors.Blue600 else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(if (isUser) 20.dp else 4.dp, if (isUser) 4.dp else 20.dp, 20.dp, 20.dp)) {
                                Text(msg.content, style = MaterialTheme.typography.bodyMedium,
                                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(12.dp, 10.dp))
                            }
                            if (msg.sourceDates.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    msg.sourceDates.take(3).forEach { d ->
                                        AssistChip(onClick = { onPage(d) }, label = { Text(d, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                if (thinking) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).background(CompassColors.Blue600, RoundedCornerShape(8.dp)), Alignment.Center) { Text("✨", style = MaterialTheme.typography.labelLarge) }
                            Spacer(Modifier.width(8.dp))
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    repeat(3) { i ->
                                        val inf = rememberInfiniteTransition(label = "d$i")
                                        val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse), label = "a$i")
                                        Box(Modifier.size(8.dp).background(CompassColors.Blue400.copy(alpha = a), RoundedCornerShape(4.dp)))
                                        if (i < 2) Spacer(Modifier.width(4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp).imePadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, Modifier.weight(1f),
                    placeholder = { Text("Ask about your diary…") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { val q = input.trim(); if (q.isNotBlank()) { input = ""; viewModel.ask(q) } },
                    enabled = input.isNotBlank() && !thinking,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = CompassColors.Blue600)
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
            }
        }
    }
}
