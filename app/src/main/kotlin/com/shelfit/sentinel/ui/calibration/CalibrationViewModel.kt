package com.shelfit.sentinel.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import com.shelfit.sentinel.trigger.audio.CalibrationSpec
import com.shelfit.sentinel.trigger.audio.CalibrationStage
import com.shelfit.sentinel.trigger.audio.ClapCalibration
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
import com.shelfit.sentinel.trigger.audio.ClapProfile
import com.shelfit.sentinel.trigger.audio.DoubleClapConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the guided flow has got to. */
sealed interface CalibrationStep {

    /** Explaining what is about to happen. Nothing is recording. */
    data object Intro : CalibrationStep

    /** Ambient measurement or clap collection in progress. */
    data class Measuring(val stage: CalibrationStage) : CalibrationStep

    /** Measurement finished. Nothing saved yet. */
    data class Review(
        val calibration: ClapCalibration,
        val profile: ClapProfile,
    ) : CalibrationStep

    /** The new configuration is live but unsaved, so the user can try it. */
    data class Trial(
        val calibration: ClapCalibration,
        val profile: ClapProfile,
    ) : CalibrationStep

    data class Failed(val reason: String) : CalibrationStep

    data object Saved : CalibrationStep
}

/**
 * Drives the guided calibration flow.
 *
 * Owns the sequencing that makes the flow safe: the microphone serves one client at a
 * time, so detection is stopped before measuring, and a trial configuration is always
 * withdrawn when the screen goes away — an unsaved profile silently left in force
 * would be a genuine bug rather than a cosmetic one.
 */
class CalibrationViewModel(private val container: AppContainer) : ViewModel() {

    private val _step = MutableStateFlow<CalibrationStep>(CalibrationStep.Intro)
    val step: StateFlow<CalibrationStep> = _step

    /** Live detector state, used only during [CalibrationStep.Trial]. */
    val diagnostics: StateFlow<ClapDiagnostics> = container.doubleClapDetector.diagnostics
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ClapDiagnostics(),
        )

    private var measurementJob: Job? = null

    /** Whether detection was running before we interrupted it, so it can be restored. */
    private var wasDetecting = false

    fun start() {
        measurementJob?.cancel()
        wasDetecting = container.triggerEngine.isRunning.value

        // The calibrator and the detector share one AudioRecord source.
        container.stopDetection()

        measurementJob = viewModelScope.launch {
            container.clapCalibrator
                .run(CalibrationSpec(), container.wallClock.epochMillis())
                .collect { stage ->
                    _step.value = when (stage) {
                        is CalibrationStage.Complete ->
                            CalibrationStep.Review(stage.calibration, stage.profile)

                        is CalibrationStage.Failed -> CalibrationStep.Failed(stage.reason)
                        else -> CalibrationStep.Measuring(stage)
                    }
                }
        }
    }

    /** Runs the unsaved profile through the real pipeline, vibration included. */
    fun beginTrial() {
        val review = _step.value as? CalibrationStep.Review ?: return
        viewModelScope.launch {
            container.startTrial(configurationFor(review.profile))
            _step.value = CalibrationStep.Trial(review.calibration, review.profile)
        }
    }

    fun endTrial() {
        val trial = _step.value as? CalibrationStep.Trial ?: return
        container.endTrial()
        _step.value = CalibrationStep.Review(trial.calibration, trial.profile)
    }

    fun save() {
        val calibration = when (val current = _step.value) {
            is CalibrationStep.Review -> current.calibration
            is CalibrationStep.Trial -> current.calibration
            else -> return
        }
        viewModelScope.launch {
            container.settingsRepository.saveCalibration(calibration)
            container.eventLog.record(
                DiagnosticEvent.Kind.CALIBRATION_SAVED,
                "${calibration.sampleCount} claps, " +
                    "${calibration.headroomDecibels.toInt()} dB headroom",
            )
            container.endTrial()
            if (wasDetecting) container.startDetection()
            _step.value = CalibrationStep.Saved
        }
    }

    /**
     * Abandons the run without saving and returns to the intro. Detection is left
     * stopped; the user restarts it from the dashboard, which is less surprising than
     * having it come back on by itself.
     */
    fun discard() {
        measurementJob?.cancel()
        measurementJob = null
        container.endTrial()
        _step.value = CalibrationStep.Intro
    }

    private suspend fun configurationFor(profile: ClapProfile) = DoubleClapConfiguration(
        enabled = true,
        sensitivity = container.settingsRepository.settings.first().sensitivity,
        profile = profile,
    )

    override fun onCleared() {
        // Never leave an unsaved configuration driving the engine.
        container.endTrial()
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CalibrationViewModel(container) }
        }
    }
}
