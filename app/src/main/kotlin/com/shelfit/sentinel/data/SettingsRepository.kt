package com.shelfit.sentinel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.trigger.audio.DoubleClapConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** User-facing settings. Small and flat, which is what DataStore Preferences suits. */
data class SentinelSettings(
    val keepScreenOn: Boolean = false,
    val doubleClapEnabled: Boolean = true,
    val doubleClapSensitivity: Float = 0.5f,
)

/**
 * Translates settings into the per-trigger configuration the engine consumes.
 *
 * A new trigger adds one entry here. This is the only place the settings layer and
 * the trigger layer meet.
 */
fun SentinelSettings.triggerConfigurations(): Map<TriggerId, TriggerConfiguration> = mapOf(
    TriggerId.DoubleClap to DoubleClapConfiguration(
        enabled = doubleClapEnabled,
        sensitivity = doubleClapSensitivity,
    ),
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sentinel_settings",
)

/**
 * Persists lightweight preferences.
 *
 * Reads recover from a corrupt or unreadable file by falling back to defaults: a
 * device meant to run unattended for weeks should not be bricked by one bad write.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.settingsDataStore

    val settings: Flow<SentinelSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val defaults = SentinelSettings()
            SentinelSettings(
                keepScreenOn = preferences[Keys.KeepScreenOn] ?: defaults.keepScreenOn,
                doubleClapEnabled = preferences[Keys.DoubleClapEnabled]
                    ?: defaults.doubleClapEnabled,
                doubleClapSensitivity = preferences[Keys.DoubleClapSensitivity]
                    ?: defaults.doubleClapSensitivity,
            )
        }

    suspend fun setKeepScreenOn(enabled: Boolean) = edit(Keys.KeepScreenOn, enabled)

    suspend fun setDoubleClapEnabled(enabled: Boolean) = edit(Keys.DoubleClapEnabled, enabled)

    suspend fun setDoubleClapSensitivity(sensitivity: Float) =
        edit(Keys.DoubleClapSensitivity, sensitivity.coerceIn(0f, 1f))

    private suspend fun <T> edit(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences -> preferences[key] = value }
    }

    private object Keys {
        val KeepScreenOn = booleanPreferencesKey("keep_screen_on")
        val DoubleClapEnabled = booleanPreferencesKey("double_clap_enabled")
        val DoubleClapSensitivity = floatPreferencesKey("double_clap_sensitivity")
    }
}
