package com.compass.diary

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.util.SongClassifier
import com.compass.diary.util.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class CompassApplication : Application(), Configuration.Provider {

    @Inject lateinit var diaryRepository: DiaryRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var songClassifier: SongClassifier
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Auto-locks any page left unsaved from a previous day, every time the app cold-starts.
        appScope.launch { diaryRepository.autoLockPastEntries() }
        // Sorts any new / not-yet-sorted songs into mood + era in the background (needs the Gemini key).
        appScope.launch { delay(8_000); songClassifier.kick() }
        // Hourly clock-aligned Drive sync, runs even when the app is closed.
        SyncScheduler.scheduleHourlySync(this)
    }
}
