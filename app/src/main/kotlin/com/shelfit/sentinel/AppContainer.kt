package com.shelfit.sentinel

import android.content.Context
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.rule.AutomationCoordinator
import com.shelfit.sentinel.core.sensor.SensorStatusProvider
import com.shelfit.sentinel.core.trigger.TriggerEngine
import com.shelfit.sentinel.core.trigger.TriggerRegistry
import com.shelfit.sentinel.data.RuleRepository
import com.shelfit.sentinel.data.SettingsRepository
import com.shelfit.sentinel.data.triggerConfigurations
import com.shelfit.sentinel.platform.AndroidSensorStatusProvider
import com.shelfit.sentinel.platform.LogActionExecutor
import com.shelfit.sentinel.platform.SystemMonotonicClock
import com.shelfit.sentinel.platform.VibrationActionExecutor
import com.shelfit.sentinel.platform.audio.AudioRecordInput
import com.shelfit.sentinel.trigger.audio.DoubleClapDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * Manual dependency container, held by [SentinelApplication] for the process
 * lifetime.
 *
 * Hand-wired on purpose: the graph is a dozen objects and a DI framework would add
 * build time and indirection without removing any real work.
 *
 * **This is the extension point.** A new trigger type is registered by adding its
 * detector to [triggerRegistry]; a new capability is registered by adding its
 * executor to the action dispatcher. Nothing else in the app needs to change.
 */
class AppContainer(context: Context) {

    /**
     * Outlives any screen — detection must survive the UI going away. `Default`
     * rather than `Main`: sensor processing is CPU work and must stay off the UI
     * thread.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sensorStatusProvider: SensorStatusProvider = AndroidSensorStatusProvider(context)

    val settingsRepository = SettingsRepository(context)

    val ruleRepository = RuleRepository()

    /**
     * Exposed as its concrete type so the detector test screen can read
     * [DoubleClapDetector.diagnostics]. The engine still only sees a
     * `TriggerDetector`.
     */
    val doubleClapDetector = DoubleClapDetector(
        audioInput = AudioRecordInput(context),
        sensorStatus = sensorStatusProvider,
        clock = SystemMonotonicClock,
    )

    val triggerRegistry = TriggerRegistry(
        detectors = listOf(
            doubleClapDetector,
            // Register future detectors here — camera gestures, ambient light,
            // accelerometer. Each one only implements TriggerDetector.
        ),
    )

    val triggerEngine = TriggerEngine(
        registry = triggerRegistry,
        scope = applicationScope,
    )

    private val actionDispatcher = ActionDispatcher(
        executors = listOf(
            VibrationActionExecutor(context) {
                settingsRepository.settings.first().hapticFeedbackEnabled
            },
            LogActionExecutor(),
            // Register future executors here — Google Home, webhooks, notifications.
        ),
    )

    val automationCoordinator = AutomationCoordinator(
        events = triggerEngine.events,
        rules = ruleRepository.rules,
        dispatcher = actionDispatcher,
        clock = SystemMonotonicClock,
        scope = applicationScope,
    )

    init {
        // Cheap to leave running: it only suspends on the engine's event flow, which
        // produces nothing until a detector is started.
        automationCoordinator.start()
    }

    /**
     * Starts detection with the user's current settings.
     *
     * Lives here rather than in a ViewModel because both the dashboard and the
     * detector test screen start the same engine, and neither should own the
     * settings-to-configuration mapping.
     */
    suspend fun startDetection() {
        triggerEngine.start(settingsRepository.settings.first().triggerConfigurations())
    }

    fun stopDetection() {
        triggerEngine.stop()
    }

    /**
     * Reloads configuration into a running engine.
     *
     * Detectors receive their configuration once, when their flow is collected, so
     * changing sensitivity means restarting capture. Cheap — reopening the
     * microphone takes a fraction of a second — but not free, so callers should
     * apply a slider's final value rather than every intermediate one.
     */
    suspend fun restartDetection() {
        if (!triggerEngine.isRunning.value) return
        stopDetection()
        startDetection()
    }
}
