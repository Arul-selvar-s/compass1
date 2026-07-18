package com.compass.diary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.compass.diary.data.local.entity.NoteMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM note_messages WHERE dateKey = :dateKey ORDER BY sentAt ASC")
    fun getMessagesForDate(dateKey: String): Flow<List<NoteMessageEntity>>

    @Query("SELECT * FROM note_messages WHERE dateKey = :dateKey ORDER BY sentAt ASC")
    suspend fun getMessagesForDateOnce(dateKey: String): List<NoteMessageEntity>

    @Query("SELECT * FROM note_messages ORDER BY sentAt ASC")
    suspend fun getAllForBackup(): List<NoteMessageEntity>

    @Insert
    suspend fun insertMessage(m: NoteMessageEntity): Long

    @Query("SELECT * FROM note_messages WHERE dateKey = :dateKey AND text = :text AND sentAt = :sentAt LIMIT 1")
    suspend fun findMatch(dateKey: String, text: String, sentAt: Long): NoteMessageEntity?
}
