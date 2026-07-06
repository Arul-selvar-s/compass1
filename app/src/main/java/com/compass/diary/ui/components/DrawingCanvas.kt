package com.compass.diary.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.compass.diary.ui.theme.CompassColors
import com.google.gson.Gson

data class DrawPath(val points: List<Pair<Float, Float>>, val color: Long, val strokeWidth: Float, val toolType: String)

@Composable
fun DrawingCanvas(onSave: (String) -> Unit, onClose: () -> Unit) {
    var paths     by remember { mutableStateOf(listOf<DrawPath>()) }
    var redoStack by remember { mutableStateOf(listOf<DrawPath>()) }
    var current   by remember { mutableStateOf(listOf<Offset>()) }
    var tool      by remember { mutableStateOf("PEN") }
    var color     by remember { mutableStateOf(Color.Black) }
    var strokeW   by remember { mutableStateOf(4f) }
    var showPalette by remember { mutableStateOf(false) }

    val drawColor  = if (tool == "ERASER") Color.White else color
    val drawStroke = when (tool) { "PENCIL" -> strokeW; "PEN" -> strokeW * 2f; "MARKER" -> strokeW * 6f; else -> strokeW * 10f }
    val drawAlpha  = if (tool == "MARKER") 0.4f else 1f

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                Text("Drawing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (paths.isNotEmpty()) { redoStack = redoStack + paths.last(); paths = paths.dropLast(1) } }, enabled = paths.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { if (redoStack.isNotEmpty()) { paths = paths + redoStack.last(); redoStack = redoStack.dropLast(1) } }, enabled = redoStack.isNotEmpty()) { Icon(Icons.Default.Redo, "Redo") }
                IconButton(onClick = { paths = emptyList(); redoStack = emptyList() }) { Icon(Icons.Default.Delete, "Clear") }
                FilledTonalButton(onClick = { onSave(Gson().toJson(paths)) }) { Text("Insert") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(Color.White)
            .pointerInput(tool, color, strokeW) {
                detectDragGestures(
                    onDragStart = { offset -> current = listOf(offset); redoStack = emptyList() },
                    onDrag      = { change, _ -> current = current + change.position },
                    onDragEnd   = {
                        if (current.isNotEmpty()) {
                            paths = paths + DrawPath(current.map { it.x to it.y }, drawColor.copy(alpha = drawAlpha).value.toLong(), drawStroke, tool)
                            current = emptyList()
                        }
                    }
                )
            }) {
            Canvas(Modifier.fillMaxSize()) {
                paths.forEach { p ->
                    if (p.points.size >= 2) {
                        val path = Path().apply { moveTo(p.points[0].first, p.points[0].second); p.points.drop(1).forEach { lineTo(it.first, it.second) } }
                        drawPath(path, Color(p.color.toULong()), style = Stroke(p.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                if (current.size >= 2) {
                    val path = Path().apply { moveTo(current[0].x, current[0].y); current.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, drawColor.copy(alpha = drawAlpha), style = Stroke(drawStroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("✏️" to "PENCIL", "🖊️" to "PEN", "🖌️" to "MARKER", "⬜" to "ERASER").forEach { (emoji, t) ->
                        Surface(onClick = { tool = t }, shape = RoundedCornerShape(8.dp),
                            color = if (tool == t) CompassColors.Blue600 else Color.Transparent, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(emoji, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                    Box(Modifier.size(36.dp).background(color, CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { showPalette = !showPalette })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Size", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                    Slider(value = strokeW, onValueChange = { strokeW = it }, valueRange = 1f..20f, modifier = Modifier.weight(1f))
                }
                if (showPalette) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(Color.Black, Color.White, Color.Red, Color(0xFFFF6B35), Color(0xFFFFC300),
                            Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFF795548)).forEach { c ->
                            Box(Modifier.size(28.dp).background(c, CircleShape)
                                .border(if (color == c) 2.dp else 0.dp, CompassColors.Blue400, CircleShape)
                                .clickable { color = c; showPalette = false })
                        }
                    }
                }
            }
        }
    }
}
