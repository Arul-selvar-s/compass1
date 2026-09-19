package com.compass.diary.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object PlayerActionBus {
    private val _actions = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val actions: SharedFlow<String> = _actions

    suspend fun emit(action: String) { _actions.emit(action) }
}
