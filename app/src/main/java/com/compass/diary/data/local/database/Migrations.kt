package com.compass.diary.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS song_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                youtubeUrl TEXT NOT NULL,
                note TEXT,
                sender TEXT NOT NULL,
                sentAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS voice_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                audioFileName TEXT NOT NULL,
                note TEXT,
                durationMs INTEGER NOT NULL,
                sourceType TEXT NOT NULL,
                sentAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
