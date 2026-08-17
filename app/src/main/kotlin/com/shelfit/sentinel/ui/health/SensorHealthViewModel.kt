package com.shelfit.sentinel.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.sensormode.ListeningMode
import com.shelfit.sentinel.core.sensormode.SensorHealth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State for the Sensor Health screen, and the actions that fix what it reports.
 *
 * Every fix routes through [com.shelfit.sentinel.service.SensorModeController], so the
 * desired listening mode is written in one place regardless of which screen the user
 * pressed a button on.
 */
class SensorHealthViewModel(private val container: AppContainer) : ViewModel() {

    val health: StateFlow<SensorHealth> = container.sensorHealthRepository.health.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SensorHealth(),
    )

    /** Re-reads permissions and the battery exemption. Called when the screen resumes. */
    fun refresh() = container.sensorHealthRepository.refresh()

    fun enable() {
        viewModelScope.launch {
            container.sensorModeController.enable()
            refresh()
        }
    }

    fun disable() {
        viewModelScope.launch {
            container.sensorModeController.disable()
            refresh()
        }
    }

    fun pause() {
        viewModelScope.launch { container.sensorModeController.pause() }
    }

    fun resume() {
        viewModelScope.launch {
            container.sensorModeController.resume()
            refresh()
        }
    }

    fun dismissError() {
        viewModelScope.launch { container.sensorModeStore.clearError() }
    }

    /** True when the switch on this screen should read as on. */
    val isEnabled: Boolean get() = health.value.desiredMode != ListeningMode.OFF

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SensorHealthViewModel(container) }
        }
    }
}
