package com.compass.diary.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "song_messages")
data class SongMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val youtubeUrl: String,
    val note: String? = null,
    val sender: String,
    val sentAt: Long = System.currentTimeMillis(),
    val title: String? = null,
    /** Comma-separated SongTag ids ("love,melody,90s"). null = not sorted yet, "" = sorted, no tags fit. */
    val categories: String? = null,
    /** When [categories] last changed (AI or manual edit); the newest copy wins when two phones sync. */
    @ColumnInfo(defaultValue = "0") val categoriesAt: Long = 0,
    /** Failed sorting attempts on this phone (not synced); stops endless retries. */
    @ColumnInfo(defaultValue = "0") val categoryTries: Int = 0
)

@Entity(tableName = "voice_messages")
data class VoiceMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audioFileName: String,
    val note: String? = null,
    val durationMs: Long = 0,
    val sourceType: String,
    val sentAt: Long = System.currentTimeMillis()
)
