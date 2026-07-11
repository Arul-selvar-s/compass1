package com.compass.diary.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compass.diary.data.local.entity.*
import com.compass.diary.data.repository.DiaryRepository
import com.compass.diary.data.repository.DriveSync
import com.compass.diary.util.AutoSaveManager
import com.compass.diary.util.CompassSensorManager
import com.compass.diary.util.PreferencesManager
import com.compass.diary.util.SaveState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.*
import javax.inject.Inject
import kotlin.math.abs

private const val VM_M_LOCK  = "\u2060L\u2060"
private const val VM_M_DRAFT = "\u2060D\u2060"
private const val VM_M_SPANS = "\u2060S\u2060"

private fun extractPlainText(raw: String): String {
    if (!raw.contains(VM_M_LOCK)) return raw.trim()
    return try {
        val locked = raw.substringAfter(VM_M_LOCK).substringBefore(VM_M_DRAFT)
        val draft  = raw.substringAfter(VM_M_DRAFT, "").substringBefore(VM_M_SPANS)
        listOf(locked, draft).filter { it.isNotBlank() }.joinToString("\n").trim()
    } catch (e: Exception) { raw.trim() }
}

@HiltViewModel
class CompassViewModel @Inject constructor(
    private val sensor: CompassSensorManager,
    private val prefs: PreferencesManager
) : ViewModel() {

    data class UnlockAction(val targetAngle: Float, val isCorrect: Boolean)

    val heading: StateFlow<Float> = sensor.headingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    private val _unlockAction = MutableStateFlow<UnlockAction?>(null)
    val unlockAction: StateFlow<UnlockAction?> = _unlockAction

    private var savedAngle = -1f

    init {
        viewModelScope.launch {
            prefs.unlockAngle.collect { if (it != null) savedAngle = it }
        }
    }

    fun tryUnlock(entered: Float) {
        val diff = if (savedAngle < 0f) 999f
        else abs(((entered - savedAngle + 540f) % 360f) - 180f)
        _unlockAction.value = UnlockAction(entered, diff <= 0.5f)
    }

    fun resetAction() { _unlockAction.value = null }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val sensor: CompassSensorManager,
    private val prefs: PreferencesManager,
    private val driveSync: DriveSync,
    @ApplicationContext private val ctx: Context
) : ViewModel() {

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step

    val heading: StateFlow<Float> = sensor.headingFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    private val _driveStatus = MutableStateFlow("")
    val driveStatus: StateFlow<String> = _driveStatus

    fun goToStep(s: Int) { _step.value = s }

    fun saveAngle(angle: Float) {
        viewModelScope.launch { prefs.setUnlockAngle(angle) }
        _step.value = 2
    }

    fun onGoogleSignedIn(email: String) {
        viewModelScope.launch {
            prefs.setGoogleAccount(email)
            _driveStatus.value = "Restoring from Drive…"
            driveSync.downloadAndRestore().fold(
                onSuccess = { count ->
                    _driveStatus.value = if (count > 0)
                        "Restored $count pages ✓" else "No backup yet — will sync after you save"
                    kotlinx.coroutines.delay(2000); goToStep(3)
                },
                onFailure = {
                    _driveStatus.value = "Connected ✓ — will sync when you tap Save & Lock"
                    kotlinx.coroutines.delay(2000); goToStep(3)
                }
            )
        }
    }

    fun completeSetup() {
        viewModelScope.launch { prefs.setSetupComplete(true); prefs.setFirstLaunch(false) }
    }
}

@HiltViewModel
class SplashViewModel @Inject constructor(private val prefs: PreferencesManager) : ViewModel() {
    val isSetupComplete: StateFlow<Boolean> = prefs.isSetupComplete
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}

@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repo: DiaryRepository,
    private val autoSave: AutoSaveManager,
    private val driveSync: DriveSync,
    private val prefs: PreferencesManager
) : ViewModel() {

    val todayKey: StateFlow<String> = MutableStateFlow(LocalDate.now().toString())

    val allEntries: StateFlow<List<DiaryEntryEntity>> = repo.getAllEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalWordCount: StateFlow<Int> = allEntries
        .map { it.sumOf { e -> e.wordCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val streakDays: Int get() {
        val keys = allEntries.value.map { it.dateKey }.toSet()
        var s = 0; var d = LocalDate.now()
        while (keys.contains(d.toString())) { s++; d = d.minusDays(1) }
        return s
    }

    val allDateKeys: StateFlow<List<String>> = repo.getAllDateKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedKey = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentEntry: StateFlow<DiaryEntryEntity?> = _selectedKey
        .filterNotNull()
        .flatMapLatest { repo.getEntryByDate(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val saveState: StateFlow<SaveState> = autoSave.saveState

    val allStarred: StateFlow<List<StarredItemEntity>> = repo.getAllStarred()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upcomingReminders: StateFlow<List<ReminderEntity>> = repo.getUpcomingReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedReminders: StateFlow<List<ReminderEntity>> = repo.getCompletedReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { repo.autoLockPastEntries() }

        viewModelScope.launch {
            while (true) {
                val account = prefs.googleAccount.first()
                val enabled = prefs.isAutoSyncEnabled.first()
                if (!account.isNullOrBlank() && enabled) {
                    driveSync.downloadAndRestore()
                }
                kotlinx.coroutines.delay(12_000)
            }
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            val account = prefs.googleAccount.first()
            val enabled = prefs.isAutoSyncEnabled.first()
            if (!account.isNullOrBlank() && enabled) {
                driveSync.uploadAll()
            }
        }
    }

    private val _refreshStatus = MutableStateFlow<String?>(null)
    val refreshStatus: StateFlow<String?> = _refreshStatus

    fun manualRefresh() {
        viewModelScope.launch {
            _refreshStatus.value = "Refreshing…"
            val account = prefs.googleAccount.first()
            if (account.isNullOrBlank()) {
                _refreshStatus.value = "Not signed in to Google"
                kotlinx.coroutines.delay(2000)
                _refreshStatus.value = null
                return@launch
            }
            driveSync.downloadAndRestore().fold(
                onSuccess = { _refreshStatus.value = "Updated ✓" },
                onFailure = { _refreshStatus.value = "Refresh failed: ${it.message}" }
            )
            kotlinx.coroutines.delay(2000)
            _refreshStatus.value = null
        }
    }

    fun selectEntry(dateKey: String) {
        _selectedKey.value = dateKey
        viewModelScope.launch { repo.ensureEntry(dateKey) }
    }

    fun onContentChanged(dateKey: String, contentJson: String) {
        val plain = extractPlainText(contentJson)
        autoSave.onContentChanged { repo.saveContent(dateKey, contentJson, plain) }
        scheduleSync()
    }

    fun forceSave(dateKey: String, contentJson: String) {
        viewModelScope.launch {
            val plain = extractPlainText(contentJson)
            autoSave.forceSave { repo.saveContent(dateKey, contentJson, plain) }
            scheduleSync()
        }
    }

    fun saveAndLock(dateKey: String, contentJson: String) {
        viewModelScope.launch {
            val plain = extractPlainText(contentJson)
            repo.saveContent(dateKey, contentJson, plain)
            driveSync.uploadAll()
        }
    }

    fun starContent(dateKey: String, text: String) {
        viewModelScope.launch {
            repo.addStarred(StarredItemEntity(
                diaryDateKey = dateKey, contentType = "TEXT",
                contentJson  = text, preview = text.take(80)
            ))
            scheduleSync()
        }
    }

    fun starWholeDay(dateKey: String) {
        viewModelScope.launch {
            repo.starWholeDay(dateKey)
            scheduleSync()
        }
    }

    fun removeStarred(id: Long) { viewModelScope.launch { repo.removeStarred(id) } }

    fun saveDrawing(dateKey: String, pathsJson: String) {
        viewModelScope.launch {
            repo.saveDrawing(DrawingEntity(diaryDateKey = dateKey, pathsJson = pathsJson, width = 1080, height = 1920))
            scheduleSync()
        }
    }

    private val _searchQuery   = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    private val _searchResults = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())
    val searchResults: StateFlow<List<DiaryEntryEntity>> = _searchResults

    fun search(q: String) {
        _searchQuery.value = q
        if (q.isBlank()) { _searchResults.value = emptyList(); return }
        viewModelScope.launch { _searchResults.value = repo.searchEntries(q) }
    }

    fun addReminder(r: ReminderEntity) { viewModelScope.launch { repo.upsertReminder(r); scheduleSync() } }
    fun deleteReminder(r: ReminderEntity) { viewModelScope.launch { repo.deleteReminder(r); scheduleSync() } }
    fun markReminderComplete(id: Long) { viewModelScope.launch { repo.markReminderCompleted(id); scheduleSync() } }

    override fun onCleared() { super.onCleared(); autoSave.dispose(); syncJob?.cancel() }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val driveSync: DriveSync
) : ViewModel() {

    val darkMode: StateFlow<String>          = prefs.darkMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM")
    val notificationsEnabled: StateFlow<Boolean> = prefs.isNotificationsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val autoSync: StateFlow<Boolean>         = prefs.isAutoSyncEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val googleAccount: StateFlow<String?>    = prefs.googleAccount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val biometricEnabled: StateFlow<Boolean> = prefs.isBiometricEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val anthropicApiKey: StateFlow<String?>  = prefs.anthropicApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus
    private val _lastSync = MutableStateFlow("Never")
    val lastSyncLabel: StateFlow<String> = _lastSync

    fun setDarkMode(v: String) { viewModelScope.launch { prefs.setDarkMode(v) } }
    fun setNotifications(v: Boolean) { viewModelScope.launch { prefs.setNotificationsEnabled(v) } }
    fun setAutoSync(v: Boolean) { viewModelScope.launch { prefs.setAutoSync(v) } }
    fun setBiometric(v: Boolean) { viewModelScope.launch { prefs.setBiometricEnabled(v) } }
    fun setApiKey(k: String) { viewModelScope.launch { prefs.setAnthropicApiKey(k) } }

    fun syncNow() {
        viewModelScope.launch {
            _syncStatus.value = "Uploading to Drive…"
            driveSync.uploadAll().fold(
                onSuccess = {
                    _syncStatus.value = "Uploaded ✓"
                    prefs.setLastSync(System.currentTimeMillis())
                    _lastSync.value = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
                },
                onFailure = { _syncStatus.value = "Sync failed: ${it.message}" }
            )
        }
    }

    fun logout() {
        viewModelScope.launch { prefs.setGoogleAccount(null); prefs.setSetupComplete(false) }
    }
}

@HiltViewModel
class AIViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val repo: DiaryRepository
) : ViewModel() {

    data class Message(
        val id: String = UUID.randomUUID().toString(),
        val role: String,
        val content: String,
        val sourceDates: List<String> = emptyList()
    )

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _thinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _thinking

    val isApiKeyConfigured: StateFlow<Boolean> = prefs.anthropicApiKey
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val allEntries = repo.getAllEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val http = OkHttpClient()

    fun ask(question: String) {
        _messages.update { it + Message(role = "user", content = question) }
        _thinking.value = true

        viewModelScope.launch {
            val key = prefs.anthropicApiKey.first()
            if (key.isNullOrBlank()) {
                _messages.update { it + Message(role = "assistant", content = "Add your Anthropic API key in Settings → AI Assistant.") }
                _thinking.value = false
                return@launch
            }

            val context = allEntries.value
                .filter { it.plainText.isNotBlank() }
                .joinToString("\n---\n") { "Date: ${it.title}\n${it.plainText}" }
                .take(80_000)

            try {
                val body = JSONObject().apply {
                    put("model", "claude-sonnet-4-6")
                    put("max_tokens", 1024)
                    put("system", "You are a diary assistant. Answer questions about these diary entries and cite dates.\n\n$context")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", question) })
                    })
                }.toString()

                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", key)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = withContext(Dispatchers.IO) { http.newCall(req).execute() }
                val text = JSONObject(resp.body?.string() ?: "{}")
                    .getJSONArray("content").getJSONObject(0).getString("text")
                val dates = Regex("""\d{4}-\d{2}-\d{2}""").findAll(text)
                    .map { it.value }.distinct().take(3).toList()

                _messages.update { it + Message(role = "assistant", content = text, sourceDates = dates) }
            } catch (e: Exception) {
                _messages.update { it + Message(role = "assistant", content = "Error: ${e.message}") }
            }
            _thinking.value = false
        }
    }

    fun clearConversation() { _messages.value = emptyList() }

    private suspend fun Flow<String?>.first(): String? {
        var result: String? = null
        take(1).collect { result = it }
        return result
    }
}
