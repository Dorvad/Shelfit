package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig

/** A single sound accepted as a clap. */
data class ClapCandidate(
    /** Timeline position of the onset frame — not of the confirmation, which lags. */
    val onsetMillis: Long,
    /** 0.5f at the detection thresholds, rising towards 1f for a decisive clap. */
    val confidence: Float,
    val peak: Float,
    val ambientRatio: Float,
    /** Onset to the point the level returned to background. */
    val transientMillis: Long,
)

/** Why a loud sound was not accepted. Surfaced in the test screen for tuning. */
enum class ClapRejection {
    /** Sound arrived on top of continuous noise, speech or music. */
    NO_QUIET_BEFORE,

    /** Level ramped up too gradually — a voice or an instrument, not an impact. */
    SLOW_ATTACK,

    /** Peak-to-average too low: sustained rather than impulsive. */
    NOT_IMPULSIVE,

    /** Energy concentrated at low frequencies — a thud, a bang, a door. */
    LOW_FREQUENCY_RUMBLE,

    /** Did not decay back to background in time. */
    TRANSIENT_TOO_LONG,

    /** Something else started before the transient had settled. */
    NO_QUIET_AFTER,
}

/** Outcome of feeding one frame to [ClapCandidateDetector]. */
sealed interface ClapDetection {
    /** Nothing of interest, or a transient still in progress. */
    data object None : ClapDetection

    data class Candidate(val clap: ClapCandidate) : ClapDetection

    /** A loud sound that failed one of the character tests. */
    data class Rejected(val reason: ClapRejection, val atMillis: Long) : ClapDetection
}

/**
 * Decides whether a sequence of frames contains a clap.
 *
 * Runs in two phases, which is what stops it behaving like a loudness gate:
 *
 *  1. **Onset.** A frame must clear the loudness gates ([ClapProfile.minPeakAmplitude]
 *     and [ClapProfile.minAmbientRatio]) *and* look like an impact — fast attack,
 *     high crest factor, real high-frequency content, with the room quiet
 *     beforehand. A frame that is merely loud is reported as
 *     [ClapDetection.Rejected] rather than accepted.
 *  2. **Decay.** The sound then has to prove it was a clap by collapsing back to
 *     background within [ClapProfile.maxTransientMillis] and staying there for
 *     [ClapProfile.quietAfterMillis]. Speech, music and the ring of a slammed door
 *     all fail here even when their onset looks percussive.
 *
 * A candidate is therefore confirmed slightly *after* the sound ends, which is why
 * [ClapCandidate.onsetMillis] carries the onset time: all timing between claps is
 * measured onset to onset, unaffected by the confirmation lag.
 *
 * Pure Kotlin and fully deterministic — the same frames always give the same
 * answer, which is what makes the synthetic-audio tests meaningful. Stateful across
 * frames, so one instance belongs to one capture session.
 */
class ClapCandidateDetector(
    private val audio: AudioCaptureConfig,
    private val profile: ClapProfile,
) {

    private sealed interface Phase {
        data object Idle : Phase

        data class Transient(
            val onset: AudioFrameFeatures,
            val peak: Float,
            val decayedAtMillis: Long?,
            val quietMillis: Long,
        ) : Phase
    }

    private var phase: Phase = Phase.Idle
    private var quietBeforeMillis = 0L

    /** True while a sound is being measured. Shown in the test screen. */
    val transientInProgress: Boolean get() = phase is Phase.Transient

    fun reset() {
        phase = Phase.Idle
        quietBeforeMillis = 0L
    }

    fun onFrame(features: AudioFrameFeatures): ClapDetection {
        val frameMillis = audio.frameDurationMillis
        val isQuiet = features.ambientRatio <= profile.quietAmbientRatio

        // Let the noise floor settle before trusting anything.
        if (features.millisSinceStart < profile.warmUpMillis) {
            quietBeforeMillis = if (isQuiet) quietBeforeMillis + frameMillis else 0L
            return ClapDetection.None
        }

        return when (val current = phase) {
            Phase.Idle -> onIdleFrame(features, isQuiet, frameMillis)
            is Phase.Transient -> onTransientFrame(current, features, isQuiet, frameMillis)
        }
    }

    private fun onIdleFrame(
        features: AudioFrameFeatures,
        isQuiet: Boolean,
        frameMillis: Long,
    ): ClapDetection {
        val loudEnough = features.peak >= profile.minPeakAmplitude &&
            features.ambientRatio >= profile.minAmbientRatio

        if (!loudEnough) {
            quietBeforeMillis = if (isQuiet) quietBeforeMillis + frameMillis else 0L
            return ClapDetection.None
        }

        val rejection = rejectionFor(features)
        if (rejection != null) {
            quietBeforeMillis = 0L
            return ClapDetection.Rejected(rejection, features.timestampMillis)
        }

        phase = Phase.Transient(
            onset = features,
            peak = features.peak,
            decayedAtMillis = null,
            quietMillis = 0L,
        )
        quietBeforeMillis = 0L
        return ClapDetection.None
    }

    private fun onTransientFrame(
        current: Phase.Transient,
        features: AudioFrameFeatures,
        isQuiet: Boolean,
        frameMillis: Long,
    ): ClapDetection {
        if (isQuiet) {
            val decayedAt = current.decayedAtMillis ?: features.timestampMillis
            val quietMillis = current.quietMillis + frameMillis

            if (quietMillis >= profile.quietAfterMillis) {
                phase = Phase.Idle
                quietBeforeMillis = quietMillis
                return ClapDetection.Candidate(
                    ClapCandidate(
                        onsetMillis = current.onset.timestampMillis,
                        confidence = confidenceOf(current.onset),
                        peak = current.peak,
                        ambientRatio = current.onset.ambientRatio,
                        transientMillis = decayedAt - current.onset.timestampMillis,
                    ),
                )
            }

            phase = current.copy(decayedAtMillis = decayedAt, quietMillis = quietMillis)
            return ClapDetection.None
        }

        // Loud again. Either it never settled, or something new started too soon.
        if (current.decayedAtMillis != null) {
            phase = Phase.Idle
            quietBeforeMillis = 0L
            return ClapDetection.Rejected(ClapRejection.NO_QUIET_AFTER, features.timestampMillis)
        }

        val elapsed = features.timestampMillis - current.onset.timestampMillis
        if (elapsed > profile.maxTransientMillis) {
            phase = Phase.Idle
            quietBeforeMillis = 0L
            return ClapDetection.Rejected(
                ClapRejection.TRANSIENT_TOO_LONG,
                features.timestampMillis,
            )
        }

        phase = current.copy(peak = maxOf(current.peak, features.peak))
        return ClapDetection.None
    }

    /** First character test the onset fails, or null if it passes all of them. */
    private fun rejectionFor(features: AudioFrameFeatures): ClapRejection? = when {
        quietBeforeMillis < profile.quietBeforeMillis -> ClapRejection.NO_QUIET_BEFORE
        features.attackRatio < profile.minAttackRatio -> ClapRejection.SLOW_ATTACK
        features.crestFactor < profile.minCrestFactor -> ClapRejection.NOT_IMPULSIVE
        features.highFrequencyRatio < profile.minHighFrequencyRatio ->
            ClapRejection.LOW_FREQUENCY_RUMBLE

        else -> null
    }

    /**
     * How comfortably the onset cleared its thresholds, averaged across the five
     * gates. Feeds [com.shelfit.sentinel.core.trigger.TriggerEvent.confidence], so
     * a rule can demand a decisive clap rather than a marginal one.
     */
    private fun confidenceOf(onset: AudioFrameFeatures): Float {
        val margins = listOf(
            margin(onset.peak, profile.minPeakAmplitude),
            margin(onset.ambientRatio, profile.minAmbientRatio),
            margin(onset.attackRatio, profile.minAttackRatio),
            margin(onset.crestFactor, profile.minCrestFactor),
            margin(onset.highFrequencyRatio, profile.minHighFrequencyRatio),
        )
        return (BASE_CONFIDENCE + (1f - BASE_CONFIDENCE) * margins.average().toFloat())
            .coerceIn(0f, 1f)
    }

    private fun margin(value: Float, threshold: Float): Float {
        if (threshold <= 0f) return 1f
        return (((value / threshold) - 1f) / MARGIN_SPAN).coerceIn(0f, 1f)
    }

    private companion object {
        /** Confidence awarded for exactly meeting every threshold. */
        const val BASE_CONFIDENCE = 0.5f

        /** Exceeding a threshold by this multiple earns full marks for that gate. */
        const val MARGIN_SPAN = 2f
    }
}
