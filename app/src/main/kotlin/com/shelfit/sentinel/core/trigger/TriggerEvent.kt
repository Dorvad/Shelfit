package com.shelfit.sentinel.core.trigger

/**
 * A single confirmed detection, emitted by a [TriggerDetector].
 *
 * This is the only thing that crosses from the sensor half of the app into the
 * rule half. It deliberately carries no raw sensor data: no audio buffer, no
 * camera frame. Detectors interpret their input and report the conclusion.
 *
 * @param triggerId which trigger fired; rules match on this.
 * @param elapsedRealtimeMillis monotonic timestamp from the app's clock, used for
 *   rule cooldowns. Not wall-clock time, so it is unaffected by clock changes.
 * @param confidence 0f..1f. Detectors that cannot express a confidence report 1f.
 * @param detail small, non-sensitive key/value extras for display and debugging.
 */
data class TriggerEvent(
    val triggerId: TriggerId,
    val elapsedRealtimeMillis: Long,
    val confidence: Float = 1f,
    val detail: Map<String, String> = emptyMap(),
)
