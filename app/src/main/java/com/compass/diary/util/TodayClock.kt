package com.compass.diary.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What "today" is, for the whole app. It used to be read once when a screen was created, so a diary
 * left open (or a phone that slept) past midnight kept showing yesterday. MainActivity calls
 * [refresh] every few seconds while the app is on screen and every time you come back to it.
 */
@Singleton
class TodayClock @Inject constructor() {
    private val _todayKey = MutableStateFlow(LocalDate.now().toString())

    /** yyyy-MM-dd of the current day; changes by itself at midnight. */
    val todayKey: StateFlow<String> = _todayKey.asStateFlow()

    /** Re-reads the date. Cheap: safe to call as often as you like. */
    fun refresh() {
        _todayKey.value = LocalDate.now().toString()
    }
}
