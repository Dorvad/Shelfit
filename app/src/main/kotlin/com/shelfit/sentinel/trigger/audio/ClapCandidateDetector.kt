package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig

/**
 * A single sound accepted as a clap.
 *
 * Carries the measurements behind the decision as well as the decision itself, so
 * calibration can derive thresholds from real claps and the test screen can show why
 * a clap scored what it did.
 *
 * @param confidence heuristic, 0f..1f: 0.5f for a clap that exactly met every
 *   threshold, rising towards 1f the more decisively it cleared them. Not a
 *   probability, and not learned — see [ClapCandidateDetector].
 * @param transientMillis the clap's duration: onset to the point the level returned
 *   to background. In practice a measure of the room as much as the clap.
 */
data class ClapCandidate(
    /** Timeline position of the onset frame — not of the confirmation, which lags. */
    val onsetMillis: Long,
    val confidence: Float,
    val peak: Float,
    val ambientRatio: Float,
    val transientMillis: Long,
    val crestFactor: Float = 0f,
    val highFrequencyRatio: Float = 0f,
    val attackRatio: Float = 0f,
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

    /**
     * Too many separate onsets in a short window. Applause, hammering, cutlery in a
     * drawer — bursts where some pair would otherwise land in clap timing by chance.
     */
    TOO_MANY_TRANSIENTS,
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
 * Two reliability mechanisms sit on top of those phases:
 *
 *  - **An adaptive absolute gate.** The peak threshold is raised in step with the
 *    tracked ambient peak, bounded by [ClapProfile.adaptiveRangeUp], so a room that
 *    gets busier demands a louder clap instead of admitting the noise. See
 *    [ClapProfile.adaptiveMinPeak].
 *  - **Burst suppression.** More than [ClapProfile.maxTransientsPerWindow] separate
 *    onsets inside [ClapProfile.transientWindowMillis] and onsets stop being trusted
 *    until the room settles. Without it, any sufficiently dense burst — applause,
 *    hammering, a dropped handful of cutlery — eventually contains two impulses in
 *    clap timing purely by chance.
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

    /** Onset times inside the sliding window, oldest first. */
    private val recentOnsets = ArrayDeque<Long>()
    private var suppressedUntilMillis: Long? = null

    /** True while a sound is being measured. Shown in the test screen. */
    val transientInProgress: Boolean get() = phase is Phase.Transient

    /** True while onsets are being ignored because the room is a mess of them. */
    val suppressingBurst: Boolean get() = suppressedUntilMillis != null

    /**
     * The peak threshold currently in force, after ambient adaptation. Published in
     * diagnostics so the test screen can show it moving with the room.
     */
    var effectiveMinPeak: Float = profile.minPeakAmplitude
        private set

    fun reset() {
        phase = Phase.Idle
        quietBeforeMillis = 0L
        recentOnsets.clear()
        suppressedUntilMillis = null
        effectiveMinPeak = profile.minPeakAmplitude
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
        val now = features.timestampMillis
        effectiveMinPeak = profile.adaptiveMinPeak(features.ambientPeak)

        val loudEnough = features.peak >= effectiveMinPeak &&
            features.ambientRatio >= profile.minAmbientRatio

        if (!loudEnough) {
            quietBeforeMillis = if (isQuiet) quietBeforeMillis + frameMillis else 0L
            releaseSuppressionIfSettled(now)
            return ClapDetection.None
        }

        val rejection = rejectionFor(features)
        if (rejection != null) {
            quietBeforeMillis = 0L
            return ClapDetection.Rejected(rejection, features.timestampMillis)
        }

        // It looks like a clap. Is the room producing an implausible number of them?
        // Only clap-shaped onsets are counted: speech and music are already rejected
        // above, and letting them inflate this counter would make the reported reason
        // less useful without changing the outcome.
        pruneOnsets(now)
        recentOnsets.addLast(now)
        if (recentOnsets.size > profile.maxTransientsPerWindow) {
            suppressedUntilMillis = now + profile.suppressionReleaseMillis
        }
        if (suppressedUntilMillis?.let { now < it } == true) {
            quietBeforeMillis = 0L
            return ClapDetection.Rejected(ClapRejection.TOO_MANY_TRANSIENTS, now)
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
                        crestFactor = current.onset.crestFactor,
                        highFrequencyRatio = current.onset.highFrequencyRatio,
                        attackRatio = current.onset.attackRatio,
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

    private fun pruneOnsets(now: Long) {
        val cutoff = now - profile.transientWindowMillis
        while (recentOnsets.isNotEmpty() && recentOnsets.first() < cutoff) {
            recentOnsets.removeFirst()
        }
    }

    /** Lifts suppression once the window has passed and the room has gone quiet. */
    private fun releaseSuppressionIfSettled(now: Long) {
        pruneOnsets(now)
        val until = suppressedUntilMillis ?: return
        if (now >= until) suppressedUntilMillis = null
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
            margin(onset.peak, effectiveMinPeak),
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
