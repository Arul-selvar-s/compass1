package com.compass.diary.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diary_entries")
data class DiaryEntryEntity(
    @PrimaryKey val dateKey: String,
    val title: String,
    val contentJson: String = "",
    val plainText: String = "",
    val wordCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val cloudId: String? = null,
    val syncedAt: Long? = null,
    val isDeleted: Boolean = false,
    val tags: String = ""
)

@Entity(tableName = "starred_items")
data class StarredItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val diaryDateKey: String,
    val contentType: String,
    val contentJson: String,
    val preview: String,
    val starredAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val repeatType: String = "ONCE",
    val triggerAt: Long,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "version_history")
data class VersionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val diaryDateKey: String,
    val contentJson: String,
    val plainText: String,
    val wordCount: Int,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "drawings")
data class DrawingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val diaryDateKey: String,
    val pathsJson: String,
    val width: Int,
    val height: Int,
    val createdAt: Long = System.currentTimeMillis()
)
