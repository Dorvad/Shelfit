package com.shelfit.sentinel.core.trigger

import com.shelfit.sentinel.core.sensor.SensorKind

/** Lifecycle of one [TriggerDetector], surfaced directly in the dashboard. */
sealed interface TriggerState {
    /** Not running. The detector holds no sensor resources in this state. */
    data object Idle : TriggerState

    /** Acquiring the sensor. */
    data object Starting : TriggerState

    /** Running and able to emit [TriggerEvent]s. */
    data object Active : TriggerState

    /** Cannot run on this device or in the current conditions. */
    data class Unavailable(val reason: Reason, val message: String? = null) : TriggerState

    /** Started but then failed. Carries a message suitable for display. */
    data class Failed(val message: String) : TriggerState

    enum class Reason {
        MISSING_PERMISSION,
        MISSING_SENSOR,

        /** The trigger exists in the registry but its detection is not built yet. */
        NOT_IMPLEMENTED,

        DISABLED,
    }

    companion object {
        fun missingPermission(permission: String): Unavailable =
            Unavailable(Reason.MISSING_PERMISSION, permission)

        fun missingSensor(kind: SensorKind): Unavailable =
            Unavailable(Reason.MISSING_SENSOR, kind.name)
    }
}
