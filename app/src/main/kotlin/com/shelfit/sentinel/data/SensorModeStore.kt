package com.shelfit.sentinel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shelfit.sentinel.core.sensormode.ListeningMode
import com.shelfit.sentinel.core.sensormode.SensorModeError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Operational state: what the user asked Sensor Mode to do, and what has happened to it.
 *
 * Deliberately a different store from [SettingsRepository]. That one holds
 * preferences the user chose; this one holds facts about a long-running service, is
 * written from a boot receiver, and is read at process start before any UI exists. A
 * small dedicated file keeps those concerns apart and keeps the boot path cheap.
 *
 * Nothing here is or could be audio. The most detailed thing stored is an error
 * message from the audio subsystem.
 */
data class SensorModeRecord(
    val desiredMode: ListeningMode = ListeningMode.Default,
    val lastServiceStartAtEpochMillis: Long? = null,
    val lastTriggerAtEpochMillis: Long? = null,
    val lastBootAtEpochMillis: Long? = null,
    val lastError: SensorModeError? = null,
)

private val Context.sensorModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sensor_mode",
)

class SensorModeStore(context: Context) {

    private val dataStore = context.sensorModeDataStore

    val record: Flow<SensorModeRecord> = dataStore.data
        .catch { error ->
            // A corrupt file must not stop the app from starting; defaults mean
            // "Sensor Mode off", which is the safe interpretation.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            SensorModeRecord(
                desiredMode = ListeningMode.fromName(preferences[Keys.DesiredMode]),
                lastServiceStartAtEpochMillis = preferences[Keys.LastServiceStartAt],
                lastTriggerAtEpochMillis = preferences[Keys.LastTriggerAt],
                lastBootAtEpochMillis = preferences[Keys.LastBootAt],
                lastError = preferences.readError(),
            )
        }

    suspend fun setDesiredMode(mode: ListeningMode) {
        dataStore.edit { it[Keys.DesiredMode] = mode.name }
    }

    suspend fun recordServiceStart(atEpochMillis: Long) {
        dataStore.edit { it[Keys.LastServiceStartAt] = atEpochMillis }
    }

    suspend fun recordTrigger(atEpochMillis: Long) {
        dataStore.edit { it[Keys.LastTriggerAt] = atEpochMillis }
    }

    suspend fun recordBoot(atEpochMillis: Long) {
        dataStore.edit { it[Keys.LastBootAt] = atEpochMillis }
    }

    suspend fun recordError(error: SensorModeError) {
        dataStore.edit { preferences ->
            preferences[Keys.LastErrorKind] = error.kind.name
            preferences[Keys.LastErrorAt] = error.atEpochMillis
            val detail = error.detail
            if (detail == null) {
                preferences.remove(Keys.LastErrorDetail)
            } else {
                preferences[Keys.LastErrorDetail] = detail
            }
        }
    }

    suspend fun clearError() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.LastErrorKind)
            preferences.remove(Keys.LastErrorAt)
            preferences.remove(Keys.LastErrorDetail)
        }
    }

    private fun Preferences.readError(): SensorModeError? {
        val kindName = this[Keys.LastErrorKind] ?: return null
        val kind = SensorModeError.Kind.entries.firstOrNull { it.name == kindName } ?: return null
        return SensorModeError(
            kind = kind,
            atEpochMillis = this[Keys.LastErrorAt] ?: return null,
            detail = this[Keys.LastErrorDetail],
        )
    }

    private object Keys {
        val DesiredMode = stringPreferencesKey("desired_listening_mode")
        val LastServiceStartAt = longPreferencesKey("last_service_start_at")
        val LastTriggerAt = longPreferencesKey("last_trigger_at")
        val LastBootAt = longPreferencesKey("last_boot_at")
        val LastErrorKind = stringPreferencesKey("last_error_kind")
        val LastErrorAt = longPreferencesKey("last_error_at")
        val LastErrorDetail = stringPreferencesKey("last_error_detail")
    }
}
