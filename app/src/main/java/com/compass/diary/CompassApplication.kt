package com.compass.diary
import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CompassApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
