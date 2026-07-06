package com.compass.diary.ui.screens.unlock

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.SetupViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

@Composable
fun UnlockSetupScreen(
    onComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val step        by viewModel.step.collectAsState()
    val heading     by viewModel.heading.collectAsState()
    val driveStatus by viewModel.driveStatus.collectAsState()
    var angleInput  by remember { mutableStateOf("") }
    var inputError  by remember { mutableStateOf(false) }
    var signInErr   by remember { mutableStateOf("") }
    val context     = LocalContext.current

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task    = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                val email   = account?.email ?: ""
                if (email.isNotBlank()) { viewModel.onGoogleSignedIn(email); signInErr = "" }
                else { signInErr = "Could not get email — try again"; viewModel.goToStep(3) }
            } catch (e: Exception) {
                signInErr = "Sign-in failed. Tap Skip to continue without Drive."; viewModel.goToStep(3)
            }
        } else {
            viewModel.goToStep(3)
        }
    }

    fun launchSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        client.signOut().addOnCompleteListener { signInLauncher.launch(client.signInIntent) }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF060C1A), Color(0xFF0D2047)))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { i ->
                    Box(Modifier.size(if (i == step) 24.dp else 8.dp, 8.dp)
                        .background(if (i <= step) CompassColors.Blue400 else CompassColors.Silver700, CircleShape))
                }
            }

            Spacer(Modifier.height(48.dp))

            AnimatedContent(targetState = step, label = "step") { s ->
                when (s) {

                    // ── Step 0: Welcome ───────────────────────────────
                    0 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🧭", fontSize = 80.sp)
                        Spacer(Modifier.height(24.dp))
                        Text("Welcome to Compass",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, color = Color.White,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text("Your private diary, hidden inside a compass",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CompassColors.Silver400, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2047)),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                listOf("🔒  Hidden behind a real compass",
                                    "✨  Unlimited daily diary pages",
                                    "🤖  AI assistant for your entries",
                                    "☁️  Google Drive backup & restore",
                                    "🎨  Rich text, drawing & more").forEach {
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        color = CompassColors.Silver200,
                                        modifier = Modifier.padding(vertical = 5.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(40.dp))
                        Button(onClick = { viewModel.goToStep(1) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)) {
                            Text("Get Started", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    // ── Step 1: Set secret angle ──────────────────────
                    1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Set Your Secret Angle",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, color = Color.White,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text("Choose any number 0–360 as your secret.\nYou must type it EXACTLY to unlock.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CompassColors.Silver400, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))

                        Surface(color = Color(0xFF0D2047), shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Current compass heading",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CompassColors.Silver400)
                                Spacer(Modifier.height(6.dp))
                                Text("${heading.toInt()}°",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold, color = CompassColors.Blue300)
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { angleInput = heading.toInt().toString() },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("Use this direction  (${heading.toInt()}°)")
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("— or type any number —",
                            style = MaterialTheme.typography.labelMedium,
                            color = CompassColors.Silver400)
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = angleInput,
                            onValueChange = { angleInput = it.filter { c -> c.isDigit() }.take(3); inputError = false },
                            label = { Text("Secret angle  (0 – 360)") },
                            placeholder = { Text("e.g. 137") },
                            suffix = { Text("°") },
                            isError = inputError,
                            supportingText = { if (inputError) Text("Enter a number 0 – 360") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold, color = CompassColors.Blue300)
                        )

                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = {
                                val a = angleInput.toFloatOrNull()
                                if (a != null && a in 0f..360f) viewModel.saveAngle(a)
                                else inputError = true
                            },
                            enabled = angleInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)) {
                            Text("Save as My Secret Key",
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    // ── Step 2: Google Drive ──────────────────────────
                    2 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Cloud, null,
                            modifier = Modifier.size(80.dp), tint = CompassColors.Blue400)
                        Spacer(Modifier.height(24.dp))
                        Text("Connect Google Drive",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, color = Color.White,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text("Sign in with Google to back up your diary.\nInstall on a new phone → sign in → all data restored.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CompassColors.Silver400, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))

                        if (driveStatus.isNotBlank()) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D3020)),
                                modifier = Modifier.fillMaxWidth()) {
                                Text(driveStatus, style = MaterialTheme.typography.bodyMedium,
                                    color = CompassColors.Success, modifier = Modifier.padding(16.dp))
                            }
                        } else {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2047)),
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp)) {
                                    listOf("☁️  Automatic backup after every Save",
                                        "📱  Same diary on any phone",
                                        "🔄  Install fresh → sign in → restore all",
                                        "🔒  Only your Google account can read it").forEach {
                                        Text(it, style = MaterialTheme.typography.bodyMedium,
                                            color = CompassColors.Silver200,
                                            modifier = Modifier.padding(vertical = 5.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            if (signInErr.isNotBlank()) {
                                Text(signInErr, style = MaterialTheme.typography.bodySmall,
                                    color = CompassColors.Error, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(8.dp))
                            }
                            Button(onClick = { launchSignIn() },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))) {
                                Text("Sign in with Google",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { viewModel.goToStep(3) },
                                modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Text("Skip — set up later in Settings")
                            }
                        }
                    }

                    // ── Step 3: Done ──────────────────────────────────
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✅", fontSize = 80.sp)
                        Spacer(Modifier.height(24.dp))
                        Text("All Set!", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, color = Color.White,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2047)),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp)) {
                                Text("How to unlock your diary:", style = MaterialTheme.typography.titleSmall,
                                    color = CompassColors.Blue300, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(12.dp))
                                listOf("1. Open the Compass app",
                                    "2. Tap the angle number at the bottom",
                                    "3. Type your secret angle EXACTLY",
                                    "4. Tap  Find Direction",
                                    "5. Compass spins → holds → diary opens").forEach {
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        color = CompassColors.Silver200,
                                        modifier = Modifier.padding(vertical = 3.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(40.dp))
                        Button(onClick = { viewModel.completeSetup(); onComplete() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CompassColors.Blue600)) {
                            Text("Open My Diary", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}
