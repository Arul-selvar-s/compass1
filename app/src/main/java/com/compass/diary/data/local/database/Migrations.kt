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

/**
 * Adds note_messages, then copies each existing diary day's plainText into it
 * as ONE message timestamped at that day's updatedAt. Purely additive:
 * diary_entries is not modified, dropped, or rewritten.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS note_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                dateKey TEXT NOT NULL,
                text TEXT NOT NULL,
                sentAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO note_messages (dateKey, text, sentAt)
            SELECT dateKey, plainText, updatedAt FROM diary_entries
            WHERE plainText IS NOT NULL AND TRIM(plainText) != ''
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                dateKey TEXT NOT NULL,
                fileName TEXT NOT NULL,
                takenAt INTEGER NOT NULL,
                driveFileId TEXT
            )
            """.trimIndent()
        )
    }
}
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_mood (
                dateKey TEXT NOT NULL PRIMARY KEY,
                missedPercent INTEGER NOT NULL,
                lovedPercent INTEGER NOT NULL,
                savedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
