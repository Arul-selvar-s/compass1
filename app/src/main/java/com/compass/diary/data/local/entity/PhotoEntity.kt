package com.compass.diary.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val fileName: String,
    val takenAt: Long = System.currentTimeMillis(),
    val driveFileId: String? = null
)
