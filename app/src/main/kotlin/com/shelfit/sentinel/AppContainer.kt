package com.shelfit.sentinel

import android.content.Context
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.rule.AutomationCoordinator
import com.shelfit.sentinel.core.sensor.SensorStatusProvider
import com.shelfit.sentinel.core.trigger.TriggerEngine
import com.shelfit.sentinel.core.trigger.TriggerRegistry
import com.shelfit.sentinel.data.RuleRepository
import com.shelfit.sentinel.data.SettingsRepository
import com.shelfit.sentinel.platform.AndroidSensorStatusProvider
import com.shelfit.sentinel.platform.LogActionExecutor
import com.shelfit.sentinel.platform.SystemMonotonicClock
import com.shelfit.sentinel.trigger.audio.DoubleClapDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container, held by [SentinelApplication] for the process
 * lifetime.
 *
 * Hand-wired on purpose: the graph is a dozen objects and a DI framework would add
 * build time and indirection without removing any real work.
 *
 * **This is the extension point.** A new trigger type is registered by adding its
 * detector to [triggerRegistry]; a new capability is registered by adding its
 * executor to [actionDispatcher]. Nothing else in the app needs to change.
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

    val triggerRegistry = TriggerRegistry(
        detectors = listOf(
            DoubleClapDetector(sensorStatusProvider),
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
}
