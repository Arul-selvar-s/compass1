package com.compass.diary.ui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.compass.diary.ui.theme.CompassColors
import com.compass.diary.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val darkMode    by viewModel.darkMode.collectAsState()
    val notifOn     by viewModel.notificationsEnabled.collectAsState()
    val autoSync    by viewModel.autoSync.collectAsState()
    val account     by viewModel.googleAccount.collectAsState()
    val syncStatus  by viewModel.syncStatus.collectAsState()
    val lastSync    by viewModel.lastSyncLabel.collectAsState()
    val apiKey      by viewModel.anthropicApiKey.collectAsState()
    var showApiDlg  by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {

            Section("Google Drive") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(40.dp), tint = CompassColors.Blue400)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (account != null) "Connected" else "Not connected",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (account != null) CompassColors.Success else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(account ?: "Sign in to enable cloud sync",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                Div()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sync, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Auto-sync", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Last: $lastSync", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(autoSync, viewModel::setAutoSync)
                }
                Div()
                SRow(Icons.Default.CloudUpload, "Sync now",
                    subtitle = syncStatus.ifBlank { "Upload all entries to Drive" },
                    onClick = viewModel::syncNow)
            }

            Section("Security") {
                SRow(Icons.Default.Explore, "Compass lock",
                    subtitle = "Type your secret angle to unlock",
                    onClick = {})
                Div()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Biometric unlock", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    val bio by viewModel.biometricEnabled.collectAsState()
                    Switch(bio, viewModel::setBiometric)
                }
            }

            Section("AI Assistant") {
                SRow(Icons.Default.Key, "Anthropic API Key",
                    subtitle = if (apiKey.isNullOrBlank()) "Not configured" else "••••••••${apiKey?.takeLast(4)}",
                    onClick = { showApiDlg = true })
                Div()
                SRow(Icons.Default.Info, "About AI",
                    subtitle = "Uses Claude to search and summarise your diary",
                    onClick = {})
            }

            Section("Appearance") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DarkMode, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text("Theme", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    var exp by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { exp = true }) { Text(darkMode) }
                        DropdownMenu(exp, { exp = false }) {
                            listOf("SYSTEM","DARK","LIGHT").forEach { m ->
                                DropdownMenuItem({ Text(m.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { viewModel.setDarkMode(m); exp = false })
                            }
                        }
                    }
                }
            }

            Section("Notifications") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Android notifications", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Show reminder notifications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(notifOn, viewModel::setNotifications)
                }
            }

            Section("About") {
                SRow(Icons.Default.Info, "Compass", subtitle = "Version 1.0  •  No ads  •  No tracking", onClick = {})
                Div()
                SRow(Icons.Default.Lock, "Privacy", subtitle = "All data stored locally and on your own Google Drive", onClick = {})
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.logout(); onLogout() },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CompassColors.Error)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null)
                Spacer(Modifier.width(8.dp))
                Text("Sign out & lock")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showApiDlg) {
        var k by remember { mutableStateOf(apiKey ?: "") }
        var show by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showApiDlg = false },
            title = { Text("Anthropic API Key") },
            text = {
                Column {
                    Text("Get your key from console.anthropic.com", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(k, { k = it }, Modifier.fillMaxWidth(), placeholder = { Text("sk-ant-…") }, singleLine = true,
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } })
                }
            },
            confirmButton = { TextButton({ viewModel.setApiKey(k.trim()); showApiDlg = false }) { Text("Save") } },
            dismissButton = { TextButton({ showApiDlg = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = CompassColors.Blue400,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun SRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Div() = HorizontalDivider(Modifier.padding(start = 54.dp))
