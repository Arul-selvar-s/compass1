package com.compass.diary
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.navigation.compose.rememberNavController
import com.compass.diary.ui.navigation.CompassNavGraph
import com.compass.diary.ui.theme.CompassTheme
import com.compass.diary.util.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkPref by prefs.darkMode.collectAsState(initial = "SYSTEM")
            val dark = when (darkPref) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
            CompassTheme(darkTheme = dark) {
                CompassNavGraph(navController = rememberNavController())
            }
        }
    }
}
