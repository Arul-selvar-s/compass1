package com.compass.diary.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.*
import androidx.navigation.compose.*
import com.compass.diary.ui.screens.ai.AIAssistantScreen
import com.compass.diary.ui.screens.calendar.CalendarScreen
import com.compass.diary.ui.screens.compass.CompassScreen
import com.compass.diary.ui.screens.editor.DailyPageScreen
import com.compass.diary.ui.screens.home.DiaryHomeScreen
import com.compass.diary.ui.screens.reminders.RemindersScreen
import com.compass.diary.ui.screens.search.SearchScreen
import com.compass.diary.ui.screens.settings.SettingsScreen
import com.compass.diary.ui.screens.splash.SplashScreen
import com.compass.diary.ui.screens.starred.StarredScreen
import com.compass.diary.ui.screens.unlock.UnlockSetupScreen

object R {
    const val SPLASH    = "splash"
    const val SETUP      = "setup"
    const val COMPASS    = "compass"
    const val HOME       = "home"
    const val PAGE       = "page/{dateKey}"
    const val CALENDAR   = "calendar"
    const val STARRED    = "starred"
    const val SEARCH     = "search"
    const val AI         = "ai"
    const val REMINDERS  = "reminders"
    const val SETTINGS   = "settings"
    fun page(k: String) = "page/$k"
}

@Composable
fun CompassNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = R.SPLASH) {
        composable(R.SPLASH) {
            SplashScreen(
                onSetup   = { navController.navigate(R.SETUP)   { popUpTo(R.SPLASH) { inclusive = true } } },
                onCompass = { navController.navigate(R.COMPASS) { popUpTo(R.SPLASH) { inclusive = true } } }
            )
        }
        composable(R.SETUP) {
            UnlockSetupScreen(onComplete = { navController.navigate(R.COMPASS) { popUpTo(R.SETUP) { inclusive = true } } })
        }
        composable(R.COMPASS) {
            CompassScreen(onUnlocked = { navController.navigate(R.HOME) })
        }
        composable(R.HOME) {
            DiaryHomeScreen(
                onPage      = { navController.navigate(R.page(it)) },
                onCalendar  = { navController.navigate(R.CALENDAR) },
                onStarred   = { navController.navigate(R.STARRED) },
                onSearch    = { navController.navigate(R.SEARCH) },
                onAI        = { navController.navigate(R.AI) },
                onReminders = { navController.navigate(R.REMINDERS) },
                onSettings  = { navController.navigate(R.SETTINGS) }
            )
        }
        composable(R.PAGE, arguments = listOf(navArgument("dateKey") { type = NavType.StringType })) { back ->
            DailyPageScreen(
                dateKey = back.arguments?.getString("dateKey") ?: "",
                onBack  = { navController.popBackStack() },
                onAI    = { navController.navigate(R.AI) }
            )
        }
        composable(R.CALENDAR)  { CalendarScreen(onPage = { navController.navigate(R.page(it)) }, onBack = { navController.popBackStack() }) }
        composable(R.STARRED)   { StarredScreen(onPage = { navController.navigate(R.page(it)) }, onBack = { navController.popBackStack() }) }
        composable(R.SEARCH)    { SearchScreen(onPage = { navController.navigate(R.page(it)) }, onBack = { navController.popBackStack() }) }
        composable(R.AI)        { AIAssistantScreen(onBack = { navController.popBackStack() }, onPage = { navController.navigate(R.page(it)) }) }
        composable(R.REMINDERS) { RemindersScreen(onBack = { navController.popBackStack() }) }
        composable(R.SETTINGS)  { SettingsScreen(onBack = { navController.popBackStack() }, onLogout = { navController.navigate(R.COMPASS) { popUpTo(0) { inclusive = true } } }) }
    }
}
