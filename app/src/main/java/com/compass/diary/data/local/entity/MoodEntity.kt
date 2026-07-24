package com.compass.diary.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_mood")
data class MoodEntity(
    @PrimaryKey val dateKey: String,
    val missedPercent: Int,
    val lovedPercent: Int,
    val savedAt: Long = System.currentTimeMillis()
)
