package com.shelfit.sentinel.ui.claplab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.rule.AutomationOutcome
import com.shelfit.sentinel.core.trigger.TriggerState
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
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
    val sensitivity: Float = 0.5f,
    val hapticFeedbackEnabled: Boolean = true,
    /** Last rule outcome, evidence that a detection reached the action layer. */
    val lastOutcome: AutomationOutcome? = null,
)

/**
 * State for the detector test screen.
 *
 * Takes the whole [AppContainer] rather than a list of collaborators: this screen
 * deliberately observes across the entire pipeline — detector diagnostics, engine
 * state, settings, and the rule layer's outcomes — and enumerating those as
 * constructor parameters would just restate the container.
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
        lastOutcome,
    ) { listening, detectorState, diagnostics, settings, outcome ->
        ClapLabUiState(
            listening = listening,
            detectorState = detectorState,
            diagnostics = diagnostics,
            sensitivity = settings.doubleClapSensitivity,
            hapticFeedbackEnabled = settings.hapticFeedbackEnabled,
            lastOutcome = outcome,
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
     * Persists the sensitivity and reloads it into a running detector.
     *
     * Call on slider release, not on every drag: applying it restarts capture.
     */
    fun commitSensitivity(sensitivity: Float) {
        viewModelScope.launch {
            container.settingsRepository.setDoubleClapSensitivity(sensitivity)
            container.restartDetection()
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setHapticFeedbackEnabled(enabled)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ClapLabViewModel(container) }
        }
    }
}
