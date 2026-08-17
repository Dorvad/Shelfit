package com.shelfit.sentinel.core.sensor

/**
 * Physical inputs a [com.shelfit.sentinel.core.trigger.Trigger] can depend on.
 *
 * A trigger declares the sensors it needs so the UI can report availability and
 * so the engine can refuse to start a detector the device cannot serve. Add an
 * entry here when a new detector needs hardware that is not yet represented.
 */
enum class SensorKind {
    MICROPHONE,
    CAMERA,
    AMBIENT_LIGHT,
    ACCELEROMETER,
    PROXIMITY,
}

/** Whether a required sensor can actually be used right now. */
enum class SensorAvailability {
    /** Hardware present and the permissions it needs have been granted. */
    AVAILABLE,

    /** Hardware present, but a runtime permission is still missing. */
    PERMISSION_REQUIRED,

    /** The device has no such sensor. */
    UNSUPPORTED,
}

/** Availability of one sensor, as reported by a [SensorStatusProvider]. */
data class SensorStatus(
    val kind: SensorKind,
    val availability: SensorAvailability,
)

/**
 * Reports what the current device can do.
 *
 * Kept as an interface so the core module stays free of Android types and can be
 * exercised in plain JVM unit tests.
 */
interface SensorStatusProvider {
    fun statusOf(kind: SensorKind): SensorStatus

    fun statusOf(kinds: Set<SensorKind>): List<SensorStatus> = kinds.map(::statusOf)
}
