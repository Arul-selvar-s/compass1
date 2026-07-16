package com.compass.diary.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "song_messages")
data class SongMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val youtubeUrl: String,
    val note: String? = null,
    val sender: String,              // "JENMASANI" or "KUTTY_GOLU"
    val sentAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "voice_messages")
data class VoiceMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audioFileName: String,
    val note: String? = null,
    val durationMs: Long = 0,
    val sourceType: String,          // "RECORDED" or "IMPORTED"
    val sentAt: Long = System.currentTimeMillis()
)
