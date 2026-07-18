package com.compass.diary.util

import android.content.Context
import com.compass.diary.data.local.database.AppDatabase
import com.compass.diary.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        AppDatabase.create(ctx)

    @Provides fun provideDiaryDao(db: AppDatabase): DiaryDao = db.diaryDao()
    @Provides fun provideStarredDao(db: AppDatabase): StarredDao = db.starredDao()
    @Provides fun provideReminderDao(db: AppDatabase): ReminderDao = db.reminderDao()
    @Provides fun provideVersionHistoryDao(db: AppDatabase): VersionHistoryDao = db.versionHistoryDao()
    @Provides fun provideDrawingDao(db: AppDatabase): DrawingDao = db.drawingDao()
    @Provides fun provideSongDao(db: AppDatabase): SongDao = db.songDao()
    @Provides fun provideVoiceMessageDao(db: AppDatabase): VoiceMessageDao = db.voiceMessageDao()
    @Provides fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()
}
