package com.shelfit.sentinel.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import com.shelfit.sentinel.core.diagnostics.EventLog
import com.shelfit.sentinel.data.SentinelSettings
import com.shelfit.sentinel.data.SettingsRepository
import com.shelfit.sentinel.trigger.audio.SensitivityLevel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val eventLog: EventLog,
) : ViewModel() {

    val settings: StateFlow<SentinelSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SentinelSettings(),
    )

    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setKeepScreenOn(enabled)
    }

    fun setDoubleClapEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDoubleClapEnabled(enabled)
    }

    fun setSensitivity(level: SensitivityLevel) = viewModelScope.launch {
        settingsRepository.setSensitivity(level)
    }

    fun clearCalibration() = viewModelScope.launch {
        settingsRepository.clearCalibration()
        eventLog.record(DiagnosticEvent.Kind.CALIBRATION_CLEARED)
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setHapticFeedbackEnabled(enabled)
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(container.settingsRepository, container.eventLog)
            }
        }
    }
}
