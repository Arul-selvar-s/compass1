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
    private val drawingDao: DrawingDao,
    private val songDao: SongDao,
    private val voiceMessageDao: VoiceMessageDao,
    private val noteDao: NoteDao
) {
    fun getAllEntries(): Flow<List<DiaryEntryEntity>> = diaryDao.getAllEntries()
    fun getEntryByDate(dateKey: String): Flow<DiaryEntryEntity?> = diaryDao.getEntryByDate(dateKey)
    fun getAllDateKeys(): Flow<List<String>> = diaryDao.getAllDateKeys()
    suspend fun getEntryByDateOnce(dateKey: String) = diaryDao.getEntryByDateOnce(dateKey)
    suspend fun getAllForBackup() = diaryDao.getAllForBackup()

    suspend fun ensureEntry(dateKey: String) {
        if (diaryDao.getEntryByDateOnce(dateKey) == null) {
            val date = runCatching { LocalDate.parse(dateKey) }.getOrDefault(LocalDate.now())
            diaryDao.upsertEntry(DiaryEntryEntity(dateKey = dateKey, title = buildTitle(date)))
        }
    }

    fun getNoteMessages(dateKey: String): Flow<List<NoteMessageEntity>> = noteDao.getMessagesForDate(dateKey)
    suspend fun getAllNotesForBackup() = noteDao.getAllForBackup()

    suspend fun addNoteMessage(dateKey: String, text: String, sentAt: Long = System.currentTimeMillis()) {
        val clean = text.trim()
        if (clean.isBlank()) return
        ensureEntry(dateKey)
        noteDao.insertMessage(NoteMessageEntity(dateKey = dateKey, text = clean, sentAt = sentAt))
        resyncDaySummary(dateKey)
    }

    suspend fun mergeNotesFromBackup(items: List<NoteMessageEntity>) {
        val affectedDates = mutableSetOf<String>()
        items.forEach { remote ->
            val existing = noteDao.findMatch(remote.dateKey, remote.text, remote.sentAt)
            if (existing == null) {
                noteDao.insertMessage(remote)
                affectedDates += remote.dateKey
            }
        }
        affectedDates.forEach { resyncDaySummary(it) }
    }

    private suspend fun resyncDaySummary(dateKey: String) {
        val all = noteDao.getMessagesForDateOnce(dateKey)
        val combined = all.joinToString("\n\n") { it.text }
        val wc = combined.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        diaryDao.updateContent(dateKey, combined, combined, wc)
    }

    suspend fun autoLockPastEntries() {
        // No-op under the chat model — every note message is already immediate
        // and immutable the moment it's sent.
    }

    suspend fun starWholeDay(dateKey: String) {
        val entry = diaryDao.getEntryByDateOnce(dateKey) ?: return
        if (entry.plainText.isBlank()) return
        starredDao.insertStarred(
            StarredItemEntity(
                diaryDateKey = dateKey,
                contentType  = "DAY",
                contentJson  = entry.contentJson,
                preview      = entry.plainText.take(80)
            )
        )
    }

    suspend fun mergeFromBackup(entries: List<DiaryEntryEntity>) {
        entries.forEach { remote ->
            val local = diaryDao.getEntryByDateOnce(remote.dateKey)
            if (local == null || remote.updatedAt > local.updatedAt) {
                diaryDao.upsertEntry(remote)
            }
        }
    }

    suspend fun getAllStarredForBackup() = starredDao.getAllStarredForBackup()

    suspend fun mergeStarredFromBackup(items: List<StarredItemEntity>) {
        items.forEach { remote ->
            val existing = starredDao.findMatch(remote.diaryDateKey, remote.contentJson)
            if (existing == null) starredDao.insertStarred(remote.copy(id = 0))
        }
    }

    fun getAllSongs(): Flow<List<SongMessageEntity>> = songDao.getAllSongs()
    suspend fun getAllSongsForBackup() = songDao.getAllSongsForBackup()
    suspend fun addSong(song: SongMessageEntity): Long = songDao.insertSong(song)

    suspend fun mergeSongsFromBackup(items: List<SongMessageEntity>) {
        items.forEach { remote ->
            val existing = songDao.findMatch(remote.youtubeUrl, remote.sender, remote.sentAt)
            if (existing == null) songDao.insertSong(remote)
        }
    }

    fun getAllVoiceMessages(): Flow<List<VoiceMessageEntity>> = voiceMessageDao.getAllVoiceMessages()
    suspend fun getAllVoiceForBackup() = voiceMessageDao.getAllForBackup()
    suspend fun addVoiceMessage(v: VoiceMessageEntity): Long = voiceMessageDao.insertVoice(v)

    suspend fun mergeVoiceFromBackup(items: List<VoiceMessageEntity>) {
        items.forEach { remote ->
            val existing = voiceMessageDao.findMatch(remote.audioFileName)
            if (existing == null) voiceMessageDao.insertVoice(remote)
        }
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
