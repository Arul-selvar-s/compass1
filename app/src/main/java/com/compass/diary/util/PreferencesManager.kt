package com.compass.diary.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "compass_prefs")

@Singleton
class PreferencesManager @Inject constructor(@ApplicationContext private val ctx: Context) {
    companion object {
        val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val KEY_FIRST_LAUNCH   = booleanPreferencesKey("first_launch")
        val KEY_UNLOCK_ANGLE   = floatPreferencesKey("unlock_angle")
        val KEY_BIOMETRIC      = booleanPreferencesKey("biometric")
        val KEY_DARK_MODE      = stringPreferencesKey("dark_mode")
        val KEY_GOOGLE_ACCOUNT = stringPreferencesKey("google_account")
        val KEY_AUTO_SYNC      = booleanPreferencesKey("auto_sync")
        val KEY_NOTIFICATIONS  = booleanPreferencesKey("notifications")
        val KEY_ANTHROPIC_KEY  = stringPreferencesKey("anthropic_key")
        val KEY_LAST_SYNC      = longPreferencesKey("last_sync")
    }

    private val ds = ctx.dataStore

    val isSetupComplete: Flow<Boolean>   = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_SETUP_COMPLETE] ?: false }
    val isFirstLaunch: Flow<Boolean>     = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_FIRST_LAUNCH] ?: true }
    val unlockAngle: Flow<Float?>        = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_UNLOCK_ANGLE] }
    val isBiometricEnabled: Flow<Boolean> = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_BIOMETRIC] ?: false }
    val darkMode: Flow<String>           = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_DARK_MODE] ?: "SYSTEM" }
    val googleAccount: Flow<String?>     = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_GOOGLE_ACCOUNT] }
    val isAutoSyncEnabled: Flow<Boolean> = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_AUTO_SYNC] ?: true }
    val isNotificationsEnabled: Flow<Boolean> = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_NOTIFICATIONS] ?: false }
    val anthropicApiKey: Flow<String?>   = ds.data.catch { emit(emptyPreferences()) }.map { it[KEY_ANTHROPIC_KEY] }

    suspend fun setSetupComplete(v: Boolean) = ds.edit { it[KEY_SETUP_COMPLETE] = v }
    suspend fun setFirstLaunch(v: Boolean)   = ds.edit { it[KEY_FIRST_LAUNCH] = v }
    suspend fun setUnlockAngle(angle: Float) = ds.edit { it[KEY_UNLOCK_ANGLE] = angle }
    suspend fun setBiometricEnabled(v: Boolean) = ds.edit { it[KEY_BIOMETRIC] = v }
    suspend fun setDarkMode(v: String)          = ds.edit { it[KEY_DARK_MODE] = v }
    suspend fun setGoogleAccount(v: String?)    = ds.edit { if (v != null) it[KEY_GOOGLE_ACCOUNT] = v else it.remove(KEY_GOOGLE_ACCOUNT) }
    suspend fun setAutoSync(v: Boolean)         = ds.edit { it[KEY_AUTO_SYNC] = v }
    suspend fun setNotificationsEnabled(v: Boolean) = ds.edit { it[KEY_NOTIFICATIONS] = v }
    suspend fun setAnthropicApiKey(v: String)   = ds.edit { it[KEY_ANTHROPIC_KEY] = v }
    suspend fun setLastSync(v: Long)            = ds.edit { it[KEY_LAST_SYNC] = v }
}
