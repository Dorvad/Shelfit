package com.shelfit.sentinel.data

import com.shelfit.sentinel.core.sensormode.SensorHealth
import com.shelfit.sentinel.core.sensormode.SensorModeRuntime
import com.shelfit.sentinel.core.trigger.TriggerEngine
import com.shelfit.sentinel.platform.SensorEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Assembles [SensorHealth] from the three places the answer lives: what the user asked
 * for (persisted), what is actually running (this process), and what the system will
 * allow (read live).
 *
 * The system part cannot be observed — there is no callback for "the user revoked your
 * microphone permission" — so it is a snapshot that callers refresh. The health screen
 * refreshes on resume, which is exactly when a user comes back from having changed
 * something in system settings.
 */
class SensorHealthRepository(
    private val store: SensorModeStore,
    private val environment: SensorEnvironment,
    private val runtime: SensorModeRuntime,
    private val engine: TriggerEngine,
) {

    private val environmentSnapshot = MutableStateFlow(environment.snapshot())

    val health: Flow<SensorHealth> = combine(
        store.record,
        runtime.serviceRunning,
        engine.isRunning,
        environmentSnapshot,
    ) { record, serviceRunning, listening, system ->
        SensorHealth(
            desiredMode = record.desiredMode,
            serviceRunning = serviceRunning,
            listening = listening,
            microphone = system.microphone,
            notifications = system.notifications,
            batteryOptimisation = system.batteryOptimisation,
            lastServiceStartAtEpochMillis = record.lastServiceStartAtEpochMillis,
            lastTriggerAtEpochMillis = record.lastTriggerAtEpochMillis,
            lastBootAtEpochMillis = record.lastBootAtEpochMillis,
            lastError = record.lastError,
        )
    }

    /** Re-reads permissions and the battery exemption. Cheap; call it on resume. */
    fun refresh() {
        environmentSnapshot.value = environment.snapshot()
    }
}
