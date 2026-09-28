package com.compass.diary
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.compass.diary.ui.navigation.CompassNavGraph
import com.compass.diary.ui.theme.CompassTheme
import com.compass.diary.util.AppSyncManager
import com.compass.diary.util.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var appSync: AppSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // While the app is on screen: pull the other phone's changes right away
        // (also every time you come back to the app), then every 10 seconds.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try { appSync.pull() } catch (e: Exception) { /* try again next round */ }
                    delay(10_000)
                }
            }
        }

        setContent {
            val darkPref by prefs.darkMode.collectAsState(initial = "SYSTEM")
            val dark = when (darkPref) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
            CompassTheme(darkTheme = dark) {
                CompassNavGraph(navController = rememberNavController())
            }
        }
    }
}
