package com.shelfit.sentinel.ui.claplab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import com.shelfit.sentinel.core.rule.AutomationOutcome
import com.shelfit.sentinel.core.trigger.TriggerState
import com.shelfit.sentinel.data.SentinelSettings
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
import com.shelfit.sentinel.trigger.audio.ClapProfile
import com.shelfit.sentinel.trigger.audio.SensitivityLevel
import com.shelfit.sentinel.trigger.audio.scaledBy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ClapLabUiState(
    val listening: Boolean = false,
    val detectorState: TriggerState = TriggerState.Idle,
    val diagnostics: ClapDiagnostics = ClapDiagnostics(),
    val sensitivity: SensitivityLevel = SensitivityLevel.Default,
    val hapticFeedbackEnabled: Boolean = true,
    /** True when a saved calibration is providing the baseline thresholds. */
    val calibrated: Boolean = false,
    /** The thresholds actually in force, for the advanced readout. */
    val profile: ClapProfile = ClapProfile(),
    /** Last rule outcome, evidence that a detection reached the action layer. */
    val lastOutcome: AutomationOutcome? = null,
    val log: List<DiagnosticEvent> = emptyList(),
)

/**
 * State for the detector test screen.
 *
 * Takes the whole [AppContainer] rather than a list of collaborators: this screen
 * deliberately observes across the entire pipeline — detector diagnostics, engine
 * state, settings, the event log, and the rule layer's outcomes — and enumerating
 * those as constructor parameters would just restate the container.
 */
class ClapLabViewModel(private val container: AppContainer) : ViewModel() {

    private val detector = container.doubleClapDetector
    private val lastOutcome = MutableStateFlow<AutomationOutcome?>(null)

    init {
        viewModelScope.launch {
            container.automationCoordinator.outcomes.collect { lastOutcome.value = it }
        }
    }

    val uiState: StateFlow<ClapLabUiState> = combine(
        container.triggerEngine.isRunning,
        detector.state,
        detector.diagnostics,
        container.settingsRepository.settings,
        combine(lastOutcome, container.eventLog.events) { outcome, log -> outcome to log },
    ) { listening, detectorState, diagnostics, settings, outcomeAndLog ->
        ClapLabUiState(
            listening = listening,
            detectorState = detectorState,
            diagnostics = diagnostics,
            sensitivity = settings.sensitivity,
            hapticFeedbackEnabled = settings.hapticFeedbackEnabled,
            calibrated = settings.calibration != null,
            profile = settings.profileInForce(),
            lastOutcome = outcomeAndLog.first,
            log = outcomeAndLog.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ClapLabUiState(),
    )

    fun toggleListening() {
        viewModelScope.launch {
            if (container.triggerEngine.isRunning.value) {
                container.stopDetection()
            } else {
                container.startDetection()
            }
        }
    }

    /**
     * Applies a sensitivity level and reloads it into a running detector.
     *
     * Detectors read their configuration once, when collection starts, so this
     * restarts capture. Three discrete steps rather than a slider means that happens
     * at most once per tap.
     */
    fun setSensitivity(level: SensitivityLevel) {
        viewModelScope.launch {
            container.settingsRepository.setSensitivity(level)
            container.restartDetection()
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setHapticFeedbackEnabled(enabled)
        }
    }

    fun clearLog() = container.eventLog.clear()

    /** Mirrors what `triggerConfigurations()` builds, for display. */
    private fun SentinelSettings.profileInForce(): ClapProfile =
        (calibration?.toProfile() ?: ClapProfile()).scaledBy(sensitivity.scalar)

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ClapLabViewModel(container) }
        }
    }
}
