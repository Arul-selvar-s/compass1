package com.compass.diary.data.repository

import com.compass.diary.data.local.dao.*
import com.compass.diary.data.local.entity.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiaryRepository @Inject constructor(
    private val diaryDao: DiaryDao,
    private val starredDao: StarredDao,
    private val reminderDao: ReminderDao,
    private val versionHistoryDao: VersionHistoryDao,
    private val drawingDao: DrawingDao
) {
    fun getAllEntries(): Flow<List<DiaryEntryEntity>> = diaryDao.getAllEntries()
    fun getEntryByDate(dateKey: String): Flow<DiaryEntryEntity?> = diaryDao.getEntryByDate(dateKey)
    fun getAllDateKeys(): Flow<List<String>> = diaryDao.getAllDateKeys()
    suspend fun getEntryByDateOnce(dateKey: String) = diaryDao.getEntryByDateOnce(dateKey)
    suspend fun getAllForBackup() = diaryDao.getAllForBackup()

    /** Guarantees a row exists before any UPDATE — fixes silent-save bug on old/new dates */
    suspend fun ensureEntry(dateKey: String) {
        if (diaryDao.getEntryByDateOnce(dateKey) == null) {
            val date = runCatching { LocalDate.parse(dateKey) }.getOrDefault(LocalDate.now())
            diaryDao.upsertEntry(DiaryEntryEntity(dateKey = dateKey, title = buildTitle(date)))
        }
    }

    suspend fun saveContent(dateKey: String, contentJson: String, plainText: String) {
        ensureEntry(dateKey)  // ← critical fix: row always exists before UPDATE
        val wc = plainText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        diaryDao.updateContent(dateKey, contentJson, plainText, wc)
        versionHistoryDao.insertVersion(
            VersionHistoryEntity(diaryDateKey = dateKey, contentJson = contentJson, plainText = plainText, wordCount = wc)
        )
        if (versionHistoryDao.countVersions(dateKey) > 50)
            versionHistoryDao.pruneOldVersions(dateKey, System.currentTimeMillis() - 30L * 86_400_000)
    }

    suspend fun restoreFromBackup(entries: List<DiaryEntryEntity>) {
        entries.forEach { diaryDao.upsertEntry(it) }
    }

    suspend fun searchEntries(q: String) = diaryDao.searchEntries(q)

    fun getAllStarred(): Flow<List<StarredItemEntity>> = starredDao.getAllStarred()
    suspend fun addStarred(item: StarredItemEntity): Long = starredDao.insertStarred(item)
    suspend fun removeStarred(id: Long) = starredDao.deleteStarredById(id)

    fun getUpcomingReminders() = reminderDao.getUpcomingReminders()
    fun getCompletedReminders() = reminderDao.getCompletedReminders()
    suspend fun upsertReminder(r: ReminderEntity): Long = reminderDao.upsertReminder(r)
    suspend fun deleteReminder(r: ReminderEntity) = reminderDao.deleteReminder(r)
    suspend fun markReminderCompleted(id: Long) = reminderDao.markCompleted(id)

    fun getVersionHistory(dateKey: String) = versionHistoryDao.getHistoryForDate(dateKey)

    suspend fun saveDrawing(d: DrawingEntity): Long = drawingDao.upsertDrawing(d)

    private fun buildTitle(date: LocalDate): String {
        val dow = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val mon = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        return "$dow, $mon ${date.dayOfMonth}, ${date.year}"
    }
}
