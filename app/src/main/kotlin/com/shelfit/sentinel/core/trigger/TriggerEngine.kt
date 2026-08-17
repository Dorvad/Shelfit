package com.shelfit.sentinel.core.trigger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Runs the enabled detectors and merges their output into one event stream.
 *
 * The engine knows nothing about microphones, cameras or rules. It starts
 * detectors, forwards their [TriggerEvent]s, and exposes their [TriggerState]s.
 * That is what lets a new sensor be added without touching the rule or action
 * layers.
 */
class TriggerEngine(
    private val registry: TriggerRegistry,
    private val scope: CoroutineScope,
) {

    private val _events = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 16)

    /** Merged detections from every running detector. */
    val events: SharedFlow<TriggerEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Live state of every registered detector, keyed by trigger. */
    val states: StateFlow<Map<TriggerId, TriggerState>> =
        combine(registry.detectors.map { it.state }) { snapshot ->
            registry.detectors.mapIndexed { index, detector ->
                detector.trigger.id to snapshot[index]
            }.toMap()
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = registry.detectors.associate { it.trigger.id to it.state.value },
        )

    private var detectionJob: Job? = null

    /**
     * Starts every detector whose configuration is enabled. Idempotent: calling
     * it while already running does nothing.
     *
     * @param configurations per-trigger settings. Triggers absent from the map
     *   fall back to [Trigger.defaultConfiguration].
     */
    fun start(configurations: Map<TriggerId, TriggerConfiguration> = emptyMap()) {
        if (detectionJob?.isActive == true) return

        val started = registry.detectors.filter { detector ->
            val configuration = configurations[detector.trigger.id]
                ?: detector.trigger.defaultConfiguration
            configuration.enabled
        }

        detectionJob = scope.launch {
            started.forEach { detector ->
                val configuration = configurations[detector.trigger.id]
                    ?: detector.trigger.defaultConfiguration
                launch {
                    detector.events(configuration).collect { event -> _events.emit(event) }
                }
            }
        }
        _isRunning.value = true
    }

    /**
     * Cancels all detector collection. Each detector releases its sensor as its
     * flow is cancelled, so this is also the battery-saving path.
     */
    fun stop() {
        detectionJob?.cancel()
        detectionJob = null
        _isRunning.value = false
    }
}
