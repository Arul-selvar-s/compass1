package com.compass.diary

import android.app.Application
import com.compass.diary.data.repository.DiaryRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CompassApplication : Application() {

    @Inject lateinit var diaryRepository: DiaryRepository
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { diaryRepository.autoLockPastEntries() }
    }
}
