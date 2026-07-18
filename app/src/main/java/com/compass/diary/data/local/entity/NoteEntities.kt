package com.compass.diary.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "note_messages")
data class NoteMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val text: String,
    val sentAt: Long = System.currentTimeMillis()
)
