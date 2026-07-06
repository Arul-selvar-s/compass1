package com.compass.diary.data.local.dao

import androidx.room.*
import com.compass.diary.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE isDeleted = 0 ORDER BY dateKey DESC")
    fun getAllEntries(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE dateKey = :dateKey AND isDeleted = 0 LIMIT 1")
    fun getEntryByDate(dateKey: String): Flow<DiaryEntryEntity?>

    @Query("SELECT * FROM diary_entries WHERE dateKey = :dateKey AND isDeleted = 0 LIMIT 1")
    suspend fun getEntryByDateOnce(dateKey: String): DiaryEntryEntity?

    @Query("SELECT * FROM diary_entries WHERE (plainText LIKE '%' || :q || '%' OR title LIKE '%' || :q || '%') AND isDeleted = 0 ORDER BY dateKey DESC")
    suspend fun searchEntries(q: String): List<DiaryEntryEntity>

    @Query("SELECT dateKey FROM diary_entries WHERE isDeleted = 0")
    fun getAllDateKeys(): Flow<List<String>>

    @Upsert
    suspend fun upsertEntry(entry: DiaryEntryEntity)

    @Query("UPDATE diary_entries SET contentJson = :json, plainText = :plain, wordCount = :wc, updatedAt = :now WHERE dateKey = :dateKey")
    suspend fun updateContent(dateKey: String, json: String, plain: String, wc: Int, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM diary_entries WHERE isDeleted = 0 ORDER BY dateKey ASC")
    suspend fun getAllForBackup(): List<DiaryEntryEntity>
}

@Dao
interface StarredDao {
    @Query("SELECT * FROM starred_items ORDER BY starredAt DESC")
    fun getAllStarred(): Flow<List<StarredItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStarred(item: StarredItemEntity): Long

    @Query("DELETE FROM starred_items WHERE id = :id")
    suspend fun deleteStarredById(id: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE isCompleted = 0 AND triggerAt > :now ORDER BY triggerAt ASC")
    fun getUpcomingReminders(now: Long = System.currentTimeMillis()): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isCompleted = 1 ORDER BY triggerAt DESC")
    fun getCompletedReminders(): Flow<List<ReminderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminder(r: ReminderEntity): Long

    @Delete
    suspend fun deleteReminder(r: ReminderEntity)

    @Query("UPDATE reminders SET isCompleted = 1 WHERE id = :id")
    suspend fun markCompleted(id: Long)
}

@Dao
interface VersionHistoryDao {
    @Query("SELECT * FROM version_history WHERE diaryDateKey = :dateKey ORDER BY savedAt DESC")
    fun getHistoryForDate(dateKey: String): Flow<List<VersionHistoryEntity>>

    @Insert
    suspend fun insertVersion(v: VersionHistoryEntity)

    @Query("DELETE FROM version_history WHERE diaryDateKey = :dateKey AND savedAt < :before")
    suspend fun pruneOldVersions(dateKey: String, before: Long)

    @Query("SELECT COUNT(*) FROM version_history WHERE diaryDateKey = :dateKey")
    suspend fun countVersions(dateKey: String): Int
}

@Dao
interface DrawingDao {
    @Query("SELECT * FROM drawings WHERE diaryDateKey = :dateKey")
    fun getDrawingsForDate(dateKey: String): Flow<List<DrawingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDrawing(d: DrawingEntity): Long
}
