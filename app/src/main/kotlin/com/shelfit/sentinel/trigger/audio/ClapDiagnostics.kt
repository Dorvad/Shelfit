package com.shelfit.sentinel.trigger.audio

/**
 * Live view inside the clap pipeline, for the detector test screen.
 *
 * Separate from [com.shelfit.sentinel.core.trigger.TriggerState] on purpose: the
 * trigger abstraction deliberately exposes only lifecycle and confirmed events, and
 * widening it with audio-specific fields would push microphone concepts into every
 * other trigger. This is an extra, audio-only channel that the rest of the app
 * ignores.
 *
 * Carries measurements, never audio: a level number and a count, never a sample.
 *
 * @param level frame RMS, 0f..1f of full scale.
 * @param noiseFloor tracked background level, same scale.
 * @param lastRejection why the most recent loud sound was not a clap — the field
 *   that makes tuning possible without a debugger attached.
 * @param effectiveMinPeak the adaptive absolute gate. Watching this move with the
 *   room is the clearest way to see the adaptive floor working.
 */
data class ClapDiagnostics(
    val listening: Boolean = false,
    val level: Float = 0f,
    val peak: Float = 0f,
    val noiseFloor: Float = 0f,
    /** Tracked background peak, which the adaptive gate follows. */
    val ambientPeak: Float = 0f,
    /** The absolute peak gate currently in force, after ambient adaptation. */
    val effectiveMinPeak: Float = 0f,
    /** True while onsets are being ignored because the room is producing too many. */
    val suppressingBurst: Boolean = false,
    val phase: DoubleClapPhase = DoubleClapPhase.Idle,
    val candidateCount: Int = 0,
    val detectionCount: Int = 0,
    val lastCandidateAtMillis: Long? = null,
    val lastCandidateConfidence: Float? = null,
    val lastRejection: ClapRejection? = null,
    val lastGapMillis: Long? = null,
    val lastDetectionConfidence: Float? = null,
    val timelineMillis: Long = 0L,
)
