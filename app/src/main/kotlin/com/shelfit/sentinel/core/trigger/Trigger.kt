package com.shelfit.sentinel.core.trigger

import com.shelfit.sentinel.core.sensor.SensorKind

/** Stable identifier for a trigger, used as the join key between rules and events. */
@JvmInline
value class TriggerId(val value: String) {
    override fun toString(): String = value

    companion object {
        val DoubleClap = TriggerId("audio.double_clap")

        /**
         * Identifiers reserved for later stages.
         *
         * Declared, not implemented. No detector exists for any of these, so the rule
         * editor will not offer them — it offers what is in the
         * [com.shelfit.sentinel.core.trigger.TriggerRegistry], which is the single source
         * of truth for what this build can actually detect.
         *
         * They exist here so the names are settled before anything persists them: a
         * stored rule keyed on `camera.motion` written by a later version stays readable
         * by an earlier one, which shows it as an unavailable trigger rather than
         * discarding the user's rule.
         */
        val CameraMotion = TriggerId("camera.motion")
        val HandGesture = TriggerId("camera.gesture")
        val AmbientLight = TriggerId("light.ambient")
        val DeviceMovement = TriggerId("motion.accelerometer")
    }
}

/**
 * Describes *what* can be detected, not *how*.
 *
 * A [Trigger] is pure metadata: identity, a human-readable name, the hardware and
 * permissions it depends on, and the configuration it starts life with. The
 * matching [TriggerDetector] holds the sensor logic.
 *
 * Adding a new trigger type means adding a `Trigger` and a `TriggerDetector` — the
 * rule and action layers do not change.
 */
interface Trigger {
    val id: TriggerId

    /** Shown in the UI. Keep it short enough for a list row. */
    val displayName: String

    /** One line explaining what the user should do to fire this trigger. */
    val description: String

    val requiredSensors: Set<SensorKind>

    /** Android runtime permissions the detector needs, e.g. `Manifest.permission.RECORD_AUDIO`. */
    val requiredPermissions: Set<String>

    /** Used when the user has not tuned this trigger yet. */
    val defaultConfiguration: TriggerConfiguration
}
