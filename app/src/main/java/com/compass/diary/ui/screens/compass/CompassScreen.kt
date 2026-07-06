package com.compass.diary.ui.screens.compass

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.CompassViewModel
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassScreen(
    onUnlocked: () -> Unit,
    viewModel: CompassViewModel = hiltViewModel()
) {
    val heading      by viewModel.heading.collectAsState()
    val unlockAction by viewModel.unlockAction.collectAsState()
    var showInput    by remember { mutableStateOf(false) }
    var angleInput   by remember { mutableStateOf("") }
    var isAnimating  by remember { mutableStateOf(false) }

    val needleAnim = remember { Animatable(0f) }
    val displayAngle = if (isAnimating) needleAnim.value else -heading

    LaunchedEffect(unlockAction) {
        val action = unlockAction ?: return@LaunchedEffect
        isAnimating = true
        needleAnim.snapTo(-heading)

        needleAnim.animateTo(
            needleAnim.value - 360f,
            animationSpec = tween(900, easing = LinearEasing)
        )

        val target = -action.targetAngle
        val cur    = needleAnim.value % 360f
        val diff   = ((target - cur + 540f) % 360f) - 180f
        needleAnim.animateTo(
            needleAnim.value + diff,
            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)
        )

        delay(2000)
        isAnimating = false

        if (action.isCorrect) onUnlocked() else viewModel.resetAction()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF0D1B3E), Color(0xFF060C1A)), radius = 1200f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.weight(1f))

            Text("COMPASS",
                style = MaterialTheme.typography.labelSmall,
                color = CompassColors.Silver400.copy(alpha = 0.4f),
                letterSpacing = 6.sp)

            Spacer(Modifier.height(32.dp))

            Canvas(modifier = Modifier.size(280.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.width / 2

                drawCircle(Color(0xFF1E3A6E), radius = r, style = Stroke(3.dp.toPx()))
                drawCircle(Color(0xFF0D2047), radius = r - 4.dp.toPx())
                drawCircle(Color(0xFF1A3060).copy(alpha = 0.4f), radius = r * 0.85f, style = Stroke(1.dp.toPx()))

                for (a in 0 until 360 step 10) {
                    val major = a % 30 == 0
                    val tl    = if (major) r * 0.09f else r * 0.05f
                    val rad   = Math.toRadians(a.toDouble())
                    drawLine(
                        if (major) Color(0xFF4B82D0) else Color(0xFF2A4B7A),
                        Offset(c.x + (r * 0.88f) * sin(rad).toFloat(), c.y - (r * 0.88f) * cos(rad).toFloat()),
                        Offset(c.x + (r * 0.88f - tl) * sin(rad).toFloat(), c.y - (r * 0.88f - tl) * cos(rad).toFloat()),
                        if (major) 2.dp.toPx() else 1.dp.toPx()
                    )
                }

                rotate(displayAngle, pivot = c) {
                    drawPath(Path().apply {
                        moveTo(c.x, c.y + 7.dp.toPx()); lineTo(c.x - 7.dp.toPx(), c.y)
                        lineTo(c.x, c.y - r * 0.72f); lineTo(c.x + 7.dp.toPx(), c.y); close()
                    }, Color(0xFFEF4444))
                    drawPath(Path().apply {
                        moveTo(c.x, c.y - 7.dp.toPx()); lineTo(c.x - 7.dp.toPx(), c.y)
                        lineTo(c.x, c.y + r * 0.55f); lineTo(c.x + 7.dp.toPx(), c.y); close()
                    }, Color(0xFFE5E7EB))
                }
                drawCircle(Color(0xFF2563C8), radius = 10.dp.toPx(), center = c)
                drawCircle(Color(0xFF93C5FD), radius = 5.dp.toPx(),  center = c)
            }

            Spacer(Modifier.height(36.dp))

            Surface(
                onClick = { if (!isAnimating) showInput = true },
                color   = Color(0xFF1A2E5A),
                shape   = MaterialTheme.shapes.large,
                modifier = Modifier.defaultMinSize(minWidth = 180.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("${heading.toInt()}°",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = CompassColors.Blue300)
                    Text(cardinal(heading),
                        style = MaterialTheme.typography.titleMedium,
                        color = CompassColors.Silver400)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (isAnimating) "" else "Tap to find a direction",
                style = MaterialTheme.typography.labelSmall,
                color = CompassColors.Silver400.copy(alpha = 0.45f)
            )

            Spacer(Modifier.weight(1f))
        }
    }

    if (showInput) {
        ModalBottomSheet(onDismissRequest = { showInput = false; angleInput = "" }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Enter a direction",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Type your secret angle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(28.dp))

                OutlinedTextField(
                    value = angleInput,
                    onValueChange = { angleInput = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Angle  (0 – 360)") },
                    placeholder = { Text("e.g. 137") },
                    suffix = { Text("°") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold, color = CompassColors.Blue300)
                )

                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = {
                        val a = angleInput.toFloatOrNull()
                        if (a != null && a in 0f..360f) {
                            showInput = false; angleInput = ""
                            viewModel.tryUnlock(a)
                        }
                    },
                    enabled = angleInput.toFloatOrNull()?.let { it in 0f..360f } == true,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)
                ) { Text("Find Direction", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

private fun cardinal(h: Float) = when (h) {
    in 337.5f..360f, in 0f..22.5f -> "North"
    in 22.5f..67.5f   -> "North-East"
    in 67.5f..112.5f  -> "East"
    in 112.5f..157.5f -> "South-East"
    in 157.5f..202.5f -> "South"
    in 202.5f..247.5f -> "South-West"
    in 247.5f..292.5f -> "West"
    else              -> "North-West"
}
