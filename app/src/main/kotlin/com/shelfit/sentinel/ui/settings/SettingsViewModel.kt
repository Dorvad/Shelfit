package com.shelfit.sentinel.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.data.SentinelSettings
import com.shelfit.sentinel.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
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

    fun setDoubleClapSensitivity(sensitivity: Float) = viewModelScope.launch {
        settingsRepository.setDoubleClapSensitivity(sensitivity)
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setHapticFeedbackEnabled(enabled)
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository) }
        }
    }
}
