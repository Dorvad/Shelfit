package com.shelfit.sentinel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.trigger.audio.ClapCalibration
import com.shelfit.sentinel.trigger.audio.ClapProfile
import com.shelfit.sentinel.trigger.audio.DoubleClapConfiguration
import com.shelfit.sentinel.trigger.audio.SensitivityLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** User-facing settings. Small and flat, which is what DataStore Preferences suits. */
data class SentinelSettings(
    val keepScreenOn: Boolean = false,
    val doubleClapEnabled: Boolean = true,
    val sensitivity: SensitivityLevel = SensitivityLevel.Default,
    /** Buzz on a confirmed detection. Local feedback, on by default. */
    val hapticFeedbackEnabled: Boolean = true,
    /** Present once the user has completed and accepted a calibration run. */
    val calibration: ClapCalibration? = null,
    /**
     * Substitutes a pretend smart home for the real provider.
     *
     * Off by default and offered only on the developer screen: it exists so the smart-home
     * flow and its failure paths can be exercised without an account or hardware, and
     * nobody should be shown a fake home they might mistake for their own.
     */
    val simulatedSmartHome: Boolean = false,
)

/**
 * Translates settings into the per-trigger configuration the engine consumes.
 *
 * A new trigger adds one entry here. This is the only place the settings layer and
 * the trigger layer meet.
 *
 * The baseline profile comes from calibration when the user has run it, and from the
 * generic defaults otherwise. Sensitivity is then applied on top, so Low/Normal/High
 * mean the same thing relative to whichever baseline is in force.
 */
fun SentinelSettings.triggerConfigurations(): Map<TriggerId, TriggerConfiguration> = mapOf(
    TriggerId.DoubleClap to DoubleClapConfiguration(
        enabled = doubleClapEnabled,
        sensitivity = sensitivity,
        profile = calibration?.toProfile() ?: ClapProfile(),
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
 *
 * Calibration is stored as the *measurements* it took, not the thresholds derived
 * from them. [ClapCalibration.toProfile] stays the single definition of how a room
 * becomes a configuration, so improving that derivation later benefits everyone who
 * has already calibrated instead of leaving them on stale numbers. Nothing stored
 * here is or could be audio.
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
                sensitivity = SensitivityLevel.fromName(preferences[Keys.Sensitivity]),
                hapticFeedbackEnabled = preferences[Keys.HapticFeedbackEnabled]
                    ?: defaults.hapticFeedbackEnabled,
                calibration = preferences.readCalibration(),
                simulatedSmartHome = preferences[Keys.SimulatedSmartHome]
                    ?: defaults.simulatedSmartHome,
            )
        }

    suspend fun setKeepScreenOn(enabled: Boolean) = edit(Keys.KeepScreenOn, enabled)

    suspend fun setDoubleClapEnabled(enabled: Boolean) = edit(Keys.DoubleClapEnabled, enabled)

    suspend fun setSensitivity(level: SensitivityLevel) = edit(Keys.Sensitivity, level.name)

    suspend fun setHapticFeedbackEnabled(enabled: Boolean) =
        edit(Keys.HapticFeedbackEnabled, enabled)

    suspend fun setSimulatedSmartHome(enabled: Boolean) = edit(Keys.SimulatedSmartHome, enabled)

    suspend fun saveCalibration(calibration: ClapCalibration) {
        dataStore.edit { preferences ->
            preferences[Keys.CalibrationCapturedAt] = calibration.capturedAtEpochMillis
            preferences[Keys.CalibrationSampleCount] = calibration.sampleCount
            preferences[Keys.CalibrationAmbientRms] = calibration.ambientRms
            preferences[Keys.CalibrationAmbientPeak] = calibration.ambientPeak
            preferences[Keys.CalibrationClapPeakMedian] = calibration.clapPeakMedian
            preferences[Keys.CalibrationClapPeakMinimum] = calibration.clapPeakMinimum
            preferences[Keys.CalibrationAmbientRatioMinimum] = calibration.clapAmbientRatioMinimum
            preferences[Keys.CalibrationCrestMinimum] = calibration.clapCrestFactorMinimum
            preferences[Keys.CalibrationHighFrequencyMinimum] =
                calibration.clapHighFrequencyRatioMinimum
            preferences[Keys.CalibrationTransientMaximum] = calibration.clapTransientMaximumMillis
        }
    }

    /** Returns the detector to the generic defaults. */
    suspend fun clearCalibration() {
        dataStore.edit { preferences ->
            CalibrationKeys.forEach { preferences.remove(it) }
        }
    }

    private fun Preferences.readCalibration(): ClapCalibration? {
        // Every field is written in one transaction, so the timestamp is a reliable
        // marker for "a complete calibration exists".
        val capturedAt = this[Keys.CalibrationCapturedAt] ?: return null
        return ClapCalibration(
            capturedAtEpochMillis = capturedAt,
            sampleCount = this[Keys.CalibrationSampleCount] ?: return null,
            ambientRms = this[Keys.CalibrationAmbientRms] ?: return null,
            ambientPeak = this[Keys.CalibrationAmbientPeak] ?: return null,
            clapPeakMedian = this[Keys.CalibrationClapPeakMedian] ?: return null,
            clapPeakMinimum = this[Keys.CalibrationClapPeakMinimum] ?: return null,
            clapAmbientRatioMinimum = this[Keys.CalibrationAmbientRatioMinimum] ?: return null,
            clapCrestFactorMinimum = this[Keys.CalibrationCrestMinimum] ?: return null,
            clapHighFrequencyRatioMinimum =
                this[Keys.CalibrationHighFrequencyMinimum] ?: return null,
            clapTransientMaximumMillis = this[Keys.CalibrationTransientMaximum] ?: return null,
        )
    }

    private suspend fun <T> edit(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences -> preferences[key] = value }
    }

    private object Keys {
        val KeepScreenOn = booleanPreferencesKey("keep_screen_on")
        val DoubleClapEnabled = booleanPreferencesKey("double_clap_enabled")
        val Sensitivity = stringPreferencesKey("double_clap_sensitivity_level")
        val HapticFeedbackEnabled = booleanPreferencesKey("haptic_feedback_enabled")
        val SimulatedSmartHome = booleanPreferencesKey("simulated_smart_home")

        val CalibrationCapturedAt = longPreferencesKey("calibration_captured_at")
        val CalibrationSampleCount = intPreferencesKey("calibration_sample_count")
        val CalibrationAmbientRms = floatPreferencesKey("calibration_ambient_rms")
        val CalibrationAmbientPeak = floatPreferencesKey("calibration_ambient_peak")
        val CalibrationClapPeakMedian = floatPreferencesKey("calibration_clap_peak_median")
        val CalibrationClapPeakMinimum = floatPreferencesKey("calibration_clap_peak_min")
        val CalibrationAmbientRatioMinimum = floatPreferencesKey("calibration_ambient_ratio_min")
        val CalibrationCrestMinimum = floatPreferencesKey("calibration_crest_min")
        val CalibrationHighFrequencyMinimum = floatPreferencesKey("calibration_hf_min")
        val CalibrationTransientMaximum = longPreferencesKey("calibration_transient_max")
    }

    private companion object {
        val CalibrationKeys = listOf(
            Keys.CalibrationCapturedAt,
            Keys.CalibrationSampleCount,
            Keys.CalibrationAmbientRms,
            Keys.CalibrationAmbientPeak,
            Keys.CalibrationClapPeakMedian,
            Keys.CalibrationClapPeakMinimum,
            Keys.CalibrationAmbientRatioMinimum,
            Keys.CalibrationCrestMinimum,
            Keys.CalibrationHighFrequencyMinimum,
            Keys.CalibrationTransientMaximum,
        )
    }
}
