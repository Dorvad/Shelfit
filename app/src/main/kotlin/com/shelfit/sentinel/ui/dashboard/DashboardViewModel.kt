package com.shelfit.sentinel.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.sensor.SensorStatus
import com.shelfit.sentinel.core.sensormode.SensorHealth
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One trigger as the dashboard shows it. */
data class TriggerRowUi(
    val id: TriggerId,
    val name: String,
    val description: String,
    val state: TriggerState,
    /** Actions attached to this trigger by enabled rules, in rule order. */
    val actionNames: List<String>,
    /** Enabled rules for this trigger that have no action set. */
    val rulesWithoutAction: Int,
)

data class DashboardUiState(
    val isRunning: Boolean = false,
    val sensors: List<SensorStatus> = emptyList(),
    val triggers: List<TriggerRowUi> = emptyList(),
    val health: SensorHealth = SensorHealth(),
) {
    val sensorModeEnabled: Boolean get() = health.desiredMode.isEnabled
}

/**
 * Presentation state for the dashboard.
 *
 * Reads from the engine and repositories; contains no sensor logic of its own. If a
 * future change needs microphone or camera code in here, it belongs in a
 * [com.shelfit.sentinel.core.trigger.TriggerDetector] instead.
 */
class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val registry = container.triggerRegistry

    /** Only the sensors some registered trigger actually needs. Grows by itself. */
    private val relevantSensors = registry.triggers.flatMap { it.requiredSensors }.toSet()

    private val sensorSnapshot = MutableStateFlow(readSensorStatus())

    val uiState: StateFlow<DashboardUiState> = combine(
        container.triggerEngine.isRunning,
        container.triggerEngine.states,
        container.ruleRepository.rules,
        sensorSnapshot,
        container.sensorHealthRepository.health,
    ) { isRunning, triggerStates, rules, sensors, health ->
        DashboardUiState(
            isRunning = isRunning,
            sensors = sensors,
            health = health,
            triggers = registry.triggers.map { trigger ->
                val enabledRules = rules.filter { it.triggerId == trigger.id && it.enabled }
                TriggerRowUi(
                    id = trigger.id,
                    name = trigger.displayName,
                    description = trigger.description,
                    state = triggerStates[trigger.id] ?: TriggerState.Idle,
                    actionNames = enabledRules.mapNotNull { it.action?.displayName },
                    rulesWithoutAction = enabledRules.count { it.action == null },
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = DashboardUiState(),
    )

    /**
     * Re-reads hardware and permission state. Call when the screen resumes:
     * permissions can be revoked from system Settings while the app is running.
     */
    fun refreshSensorStatus() {
        sensorSnapshot.value = readSensorStatus()
        container.sensorHealthRepository.refresh()
    }

    /**
     * Turns Sensor Mode on or off.
     *
     * Goes through the foreground service rather than starting the engine directly:
     * that is what lets listening continue with the screen off, and it is the only
     * path that persists the user's intent.
     */
    fun toggleSensorMode() {
        viewModelScope.launch {
            if (uiState.value.sensorModeEnabled) {
                container.sensorModeController.disable()
            } else {
                container.sensorModeController.enable()
            }
            refreshSensorStatus()
        }
    }

    fun resumeListening() {
        viewModelScope.launch {
            container.sensorModeController.resume()
            refreshSensorStatus()
        }
    }

    private fun readSensorStatus(): List<SensorStatus> =
        container.sensorStatusProvider.statusOf(relevantSensors)

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardViewModel(container) }
        }
    }
}
