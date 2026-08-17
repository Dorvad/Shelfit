package com.shelfit.sentinel.core.sensormode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the foreground service exists right now.
 *
 * Process-scoped and deliberately not persisted: "is the service alive" is a fact about
 * this process, and a stale `true` read from disk after a kill would be worse than no
 * answer at all. Comparing this against the persisted desired mode is what tells the
 * health screen that listening needs resuming.
 */
class SensorModeRuntime {

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    fun onServiceCreated() {
        _serviceRunning.value = true
    }

    fun onServiceDestroyed() {
        _serviceRunning.value = false
    }
}
