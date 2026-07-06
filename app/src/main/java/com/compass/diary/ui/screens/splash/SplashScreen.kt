package com.compass.diary.ui.screens.splash

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.SplashViewModel
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onSetup: () -> Unit,
    onCompass: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val setupDone by viewModel.isSetupComplete.collectAsState()
    var show by remember { mutableStateOf(false) }

    val spin = rememberInfiniteTransition(label = "spin")
    val rot by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "rot"
    )

    LaunchedEffect(Unit) {
        delay(300); show = true
        delay(1800)
        if (setupDone) onCompass() else onSetup()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF060C1A), Color(0xFF0D2047)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🧭", fontSize = 80.sp, modifier = Modifier.rotate(rot * 0.05f))
            Spacer(Modifier.height(32.dp))
            AnimatedVisibility(show, enter = fadeIn(tween(600)) + slideInVertically { it / 2 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("COMPASS", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold, color = CompassColors.White, letterSpacing = 6.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Your private diary", style = MaterialTheme.typography.bodyMedium,
                        color = CompassColors.Silver400, letterSpacing = 2.sp)
                }
            }
        }
    }
}
