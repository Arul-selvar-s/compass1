package com.compass.diary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.compass.diary.data.local.entity.MoodEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodDao {
    @Query("SELECT * FROM daily_mood WHERE dateKey = :dateKey LIMIT 1")
    fun getForDate(dateKey: String): Flow<MoodEntity?>

    @Query("SELECT * FROM daily_mood WHERE dateKey = :dateKey LIMIT 1")
    suspend fun getForDateOnce(dateKey: String): MoodEntity?

    @Query("SELECT * FROM daily_mood ORDER BY dateKey ASC")
    suspend fun getAllForBackup(): List<MoodEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(m: MoodEntity)
}
