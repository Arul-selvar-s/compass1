package com.compass.diary.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.compass.diary.data.local.dao.*
import com.compass.diary.data.local.entity.*

@Database(
    entities = [
        DiaryEntryEntity::class,
        StarredItemEntity::class,
        ReminderEntity::class,
        VersionHistoryEntity::class,
        DrawingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun starredDao(): StarredDao
    abstract fun reminderDao(): ReminderDao
    abstract fun versionHistoryDao(): VersionHistoryDao
    abstract fun drawingDao(): DrawingDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "compass_diary.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
