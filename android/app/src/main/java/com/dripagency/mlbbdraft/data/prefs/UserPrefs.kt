package com.dripagency.mlbbdraft.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dripagency.mlbbdraft.data.model.RecommendMode
import com.dripagency.mlbbdraft.data.model.Role
import com.dripagency.mlbbdraft.data.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("user_prefs")

class UserPrefs(private val context: Context) {
    private object K {
        val MODE = stringPreferencesKey("mode")
        val DEFAULT_ROLE = stringPreferencesKey("default_role")
        val AUTO_LANE = booleanPreferencesKey("auto_lane")
        val OPACITY = floatPreferencesKey("overlay_opacity")
        val BRIEF_SECS = intPreferencesKey("briefing_seconds")
        val SAMPLING = longPreferencesKey("sampling_millis")
        val CALIB = booleanPreferencesKey("calibration_dump")
        val SEEDED_VERSION = longPreferencesKey("seeded_data_version")
        val LAST_CHECK = longPreferencesKey("last_update_check")
        val LAST_MSG = stringPreferencesKey("last_update_message")
        val MANIFEST_URL = stringPreferencesKey("data_manifest_url")
        val AUTO_SHOW = booleanPreferencesKey("auto_show_on_mlbb")
        val MASTERY_AT = longPreferencesKey("mastery_updated_at")
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_EMAIL = stringPreferencesKey("cloud_email")
        val CLOUD_SYNC_AT = longPreferencesKey("cloud_last_sync")
        val CLOUD_MSG = stringPreferencesKey("cloud_message")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            mode = p[K.MODE]?.let { runCatching { RecommendMode.valueOf(it) }.getOrNull() } ?: RecommendMode.FLEXIBLE,
            defaultRole = Role.parse(p[K.DEFAULT_ROLE]) ?: Role.EXP,
            autoDetectLane = p[K.AUTO_LANE] ?: true,
            overlayOpacity = p[K.OPACITY] ?: 0.92f,
            briefingSeconds = p[K.BRIEF_SECS] ?: 18,
            samplingMillis = p[K.SAMPLING] ?: 1200L,
            calibrationDump = p[K.CALIB] ?: false,
            seededDataVersion = p[K.SEEDED_VERSION] ?: 0L,
            lastUpdateCheck = p[K.LAST_CHECK] ?: 0L,
            lastUpdateMessage = p[K.LAST_MSG] ?: "",
            dataManifestUrl = p[K.MANIFEST_URL] ?: "",
            autoShowOnMlbb = p[K.AUTO_SHOW] ?: true,
            masteryUpdatedAt = p[K.MASTERY_AT] ?: 0L,
            cloudEnabled = p[K.CLOUD_ENABLED] ?: false,
            cloudEmail = p[K.CLOUD_EMAIL] ?: "",
            cloudLastSync = p[K.CLOUD_SYNC_AT] ?: 0L,
            cloudMessage = p[K.CLOUD_MSG] ?: "",
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setMode(mode: RecommendMode) = context.dataStore.edit { it[K.MODE] = mode.name; it[K.MASTERY_AT] = System.currentTimeMillis() }
    suspend fun setDefaultRole(role: Role) = context.dataStore.edit { it[K.DEFAULT_ROLE] = role.name; it[K.MASTERY_AT] = System.currentTimeMillis() }
    suspend fun touchMastery() = context.dataStore.edit { it[K.MASTERY_AT] = System.currentTimeMillis() }
    suspend fun setMasteryUpdatedAt(v: Long) = context.dataStore.edit { it[K.MASTERY_AT] = v }
    suspend fun setCloudState(enabled: Boolean, email: String, message: String) = context.dataStore.edit {
        it[K.CLOUD_ENABLED] = enabled; if (email.isNotBlank()) it[K.CLOUD_EMAIL] = email
        it[K.CLOUD_SYNC_AT] = System.currentTimeMillis(); it[K.CLOUD_MSG] = message
    }
    suspend fun setCloudMessage(message: String) = context.dataStore.edit { it[K.CLOUD_MSG] = message }
    suspend fun disableCloud() = context.dataStore.edit { it[K.CLOUD_ENABLED] = false; it[K.CLOUD_EMAIL] = ""; it[K.CLOUD_MSG] = "Disconnected" }
    suspend fun setAutoDetectLane(v: Boolean) = context.dataStore.edit { it[K.AUTO_LANE] = v }
    suspend fun setOverlayOpacity(v: Float) = context.dataStore.edit { it[K.OPACITY] = v }
    suspend fun setBriefingSeconds(v: Int) = context.dataStore.edit { it[K.BRIEF_SECS] = v }
    suspend fun setSamplingMillis(v: Long) = context.dataStore.edit { it[K.SAMPLING] = v }
    suspend fun setCalibrationDump(v: Boolean) = context.dataStore.edit { it[K.CALIB] = v }
    suspend fun setSeededDataVersion(v: Long) = context.dataStore.edit { it[K.SEEDED_VERSION] = v }
    suspend fun setDataManifestUrl(v: String) = context.dataStore.edit { it[K.MANIFEST_URL] = v.trim() }
    suspend fun setAutoShowOnMlbb(v: Boolean) = context.dataStore.edit { it[K.AUTO_SHOW] = v }
    suspend fun setUpdateStatus(message: String) = context.dataStore.edit {
        it[K.LAST_CHECK] = System.currentTimeMillis(); it[K.LAST_MSG] = message
    }
}
