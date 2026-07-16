package com.compass.diary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.compass.diary.data.local.entity.SongMessageEntity
import com.compass.diary.data.local.entity.VoiceMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM song_messages ORDER BY sentAt ASC")
    fun getAllSongs(): Flow<List<SongMessageEntity>>

    @Query("SELECT * FROM song_messages ORDER BY sentAt ASC")
    suspend fun getAllSongsForBackup(): List<SongMessageEntity>

    @Insert
    suspend fun insertSong(song: SongMessageEntity): Long

    @Query("SELECT * FROM song_messages WHERE youtubeUrl = :url AND sender = :sender AND sentAt = :sentAt LIMIT 1")
    suspend fun findMatch(url: String, sender: String, sentAt: Long): SongMessageEntity?
}

@Dao
interface VoiceMessageDao {
    @Query("SELECT * FROM voice_messages ORDER BY sentAt ASC")
    fun getAllVoiceMessages(): Flow<List<VoiceMessageEntity>>

    @Query("SELECT * FROM voice_messages ORDER BY sentAt ASC")
    suspend fun getAllForBackup(): List<VoiceMessageEntity>

    @Insert
    suspend fun insertVoice(v: VoiceMessageEntity): Long

    @Query("SELECT * FROM voice_messages WHERE audioFileName = :fileName LIMIT 1")
    suspend fun findMatch(fileName: String): VoiceMessageEntity?
}
