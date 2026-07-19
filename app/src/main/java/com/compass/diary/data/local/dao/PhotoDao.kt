package com.compass.diary.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.compass.diary.data.local.entity.PhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM daily_photos WHERE dateKey = :dateKey ORDER BY takenAt ASC")
    fun getPhotosForDate(dateKey: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM daily_photos WHERE dateKey = :dateKey ORDER BY takenAt ASC")
    suspend fun getPhotosForDateOnce(dateKey: String): List<PhotoEntity>

    @Query("SELECT * FROM daily_photos")
    fun getAllPhotos(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM daily_photos ORDER BY takenAt ASC")
    suspend fun getAllForBackup(): List<PhotoEntity>

    @Insert
    suspend fun insertPhoto(p: PhotoEntity): Long

    @Query("UPDATE daily_photos SET driveFileId = :fileId WHERE id = :id")
    suspend fun setDriveFileId(id: Long, fileId: String)

    @Query("SELECT * FROM daily_photos WHERE dateKey = :dateKey AND fileName = :fileName LIMIT 1")
    suspend fun findMatch(dateKey: String, fileName: String): PhotoEntity?
}
