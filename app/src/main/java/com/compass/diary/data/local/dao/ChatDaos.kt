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

    @Query("UPDATE song_messages SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE song_messages SET youtubeUrl = :url, note = :note WHERE id = :id")
    suspend fun update(id: Long, url: String, note: String?)

    @Query("DELETE FROM song_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM song_messages WHERE id = :id")
    suspend fun getById(id: Long): SongMessageEntity?

    /** Saves a sorting result (AI or manual) and clears the failure counter. */
    @Query("UPDATE song_messages SET categories = :categories, categoriesAt = :at, categoryTier = :tier, categoryTries = 0 WHERE id = :id")
    suspend fun setCategories(id: Long, categories: String, at: Long, tier: Int)

    @Query("UPDATE song_messages SET categoryTries = categoryTries + 1 WHERE id = :id")
    suspend fun bumpCategoryTries(id: Long)

    /** "Sort now": give every song that still needs the deep analysis a fresh set of attempts. */
    @Query("UPDATE song_messages SET categoryTries = 0 WHERE categoryTier < 2")
    suspend fun resetCategoryTries()

    /** Queue one song for a fresh deep analysis (keeps its current tags until the new result is in). */
    @Query("UPDATE song_messages SET categoryTier = 0, categoryTries = 0 WHERE id = :id")
    suspend fun markForResort(id: Long)

    /** A different video is a different song: forget the old sorting so it gets sorted again. */
    @Query("UPDATE song_messages SET categories = NULL, categoriesAt = 0, categoryTier = 0, categoryTries = 0 WHERE id = :id AND youtubeUrl != :url")
    suspend fun resetCategoriesIfUrlChanged(id: Long, url: String)

    /** Tags that arrived from the other phone / Drive (only called when they are newer). */
    @Query("UPDATE song_messages SET categories = :categories, categoriesAt = :at, categoryTier = :tier, categoryTries = 0 WHERE id = :id")
    suspend fun applyRemoteCategories(id: Long, categories: String, at: Long, tier: Int)
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

    @Query("SELECT * FROM voice_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VoiceMessageEntity?

    @Query("UPDATE voice_messages SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    @Query("DELETE FROM voice_messages WHERE id = :id")
    suspend fun deleteById(id: Long)
}
