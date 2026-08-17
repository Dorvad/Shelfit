package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import kotlin.math.pow

/**
 * How sensitive detection should be, in terms a person can reason about.
 *
 * Three named steps rather than a slider: the underlying scalar is not linear in
 * anything a user perceives, and "0.63" tells nobody whether their claps will
 * register. Advanced numeric control lives on the developer screen instead.
 *
 * @param scalar position on the 0f..1f axis that [scaledBy] understands.
 */
enum class SensitivityLevel(val scalar: Float) {
    /** Only clear, close claps. Fewest false triggers. */
    LOW(0.25f),

    /** The calibrated or default thresholds, unmodified. */
    NORMAL(0.5f),

    /** Reacts to quieter and more distant claps, at the cost of false triggers. */
    HIGH(0.75f),
    ;

    companion object {
        val Default = NORMAL

        fun fromName(name: String?): SensitivityLevel =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * Everything tunable about double clap detection.
 *
 * Every threshold in the audio pipeline is read from here — no detection constant
 * is written inline anywhere else, so retuning means editing one model rather than
 * hunting through the DSP.
 *
 * @param sensitivity the user-facing setting. It scales the *loudness* gates only;
 *   see [scaledBy].
 * @param profile the baseline thresholds. Defaults are generic; a calibrated
 *   profile from [ClapCalibration.toProfile] replaces them with values measured on
 *   the actual device, in the actual room.
 */
data class DoubleClapConfiguration(
    override val enabled: Boolean = true,
    val sensitivity: SensitivityLevel = SensitivityLevel.Default,
    val timing: DoubleClapTiming = DoubleClapTiming(),
    val profile: ClapProfile = ClapProfile(),
    val audio: AudioCaptureConfig = AudioCaptureConfig(),
) : TriggerConfiguration {

    /** The profile actually used for detection, with [sensitivity] applied. */
    val effectiveProfile: ClapProfile get() = profile.scaledBy(sensitivity.scalar)
}

/**
 * When two claps count as one double clap.
 *
 * @param minGapMillis a second clap sooner than this is a reflection of the first,
 *   not a separate gesture. Must exceed [ClapProfile.quietAfterMillis], otherwise
 *   the second clap can land inside the first one's settle window.
 * @param maxGapMillis after this the pending first clap expires and the next clap
 *   starts a fresh pair.
 * @param cooldownMillis dead time after a detection. Stops the tail of a burst of
 *   claps from being read as a second gesture.
 */
data class DoubleClapTiming(
    val minGapMillis: Long = 120L,
    val maxGapMillis: Long = 900L,
    val cooldownMillis: Long = 1_500L,
) {
    init {
        require(minGapMillis in 1 until maxGapMillis) {
            "minGapMillis must be positive and below maxGapMillis"
        }
        require(cooldownMillis >= 0) { "cooldownMillis cannot be negative" }
    }
}

/**
 * What a single clap has to look like.
 *
 * These describe the *character* of the sound rather than how loud it is, which is
 * why most of them are untouched by [DoubleClapConfiguration.sensitivity]. A clap
 * is a broadband impulse: near-instant attack, high peak-to-average ratio,
 * substantial high-frequency content, decaying back to the room's background level
 * within a fraction of a second, with quiet either side of it.
 *
 * @param minPeakAmplitude absolute floor, 0f..1f of full scale. Stops the detector
 *   reacting to microphone self-noise in a silent room. Scaled by sensitivity, and
 *   the parameter most likely to need adjusting per device.
 * @param minAmbientRatio how far frame RMS must exceed the tracked background
 *   level. This is what makes detection work in both a quiet and a noisy room.
 *   Scaled by sensitivity.
 * @param minAttackRatio frame peak divided by the previous frame's peak. A clap
 *   reaches full amplitude in about a millisecond; speech and music ramp up over
 *   tens of milliseconds. Scaled by sensitivity.
 * @param minCrestFactor peak divided by RMS within the frame. Impulsive sounds
 *   score high; sustained tones and music sit near 1.5–3.
 * @param minHighFrequencyRatio normalised energy of the first-difference signal,
 *   0f..1f — a cheap high-pass measure standing in for an FFT. Roughly
 *   `sin²(pi * f / sampleRate)` for a tone at `f`, so a 150 Hz door thud scores
 *   about 0.001 while a broadband clap scores 0.15–0.5. This is the main defence
 *   against low-frequency bangs.
 * @param maxTransientMillis a clap must have decayed back to background within
 *   this window. Rejects sustained sounds and the ring of a slammed door.
 * @param quietBeforeMillis required background-level run before the onset. Rejects
 *   peaks riding on top of continuous speech or music.
 * @param quietAfterMillis required background-level run after the decay, before
 *   the clap is confirmed.
 * @param quietAmbientRatio what counts as "background": RMS within this multiple
 *   of the tracked noise floor.
 * @param noiseFloorFallMillis time constant for the noise floor following the
 *   level down. Fast, so the detector adapts quickly when a room falls quiet.
 * @param noiseFloorRiseMillis time constant for the noise floor following the
 *   level up. Deliberately slow: a 100 ms clap barely moves it, while music
 *   playing for several seconds does, which is how sustained noise stops
 *   registering as a transient.
 * @param warmUpMillis detection is suppressed for this long after capture starts,
 *   while the noise floor settles.
 * @param adaptiveAmbientMargin the absolute peak gate is raised to at least this
 *   multiple of the tracked ambient *peak*. This is what lets the detector tighten
 *   up when a room gets busier instead of accepting the noise. See [adaptiveRangeUp].
 * @param adaptiveRangeUp the most the adaptive gate may exceed [minPeakAmplitude],
 *   as a multiple. Bounding it is the point: an unbounded gate would drift with the
 *   room until claps stopped registering at all.
 * @param adaptiveRangeDown how far below [minPeakAmplitude] the adaptive gate may
 *   fall, as a divisor. 1f means never — the default. A quieter room is already
 *   handled by [minAmbientRatio], which becomes *easier* to satisfy as the noise
 *   floor drops, so lowering the absolute gate as well would buy sensitivity nobody
 *   asked for and pay for it in false positives at 3am.
 * @param maxTransientsPerWindow how many separate loud onsets may occur inside
 *   [transientWindowMillis] before the detector stops trusting them. A double clap
 *   is two and a triple is three, so this only engages on genuine bursts —
 *   applause, hammering, cutlery, a dog's claws on a hard floor.
 * @param transientWindowMillis sliding window for the onset count above.
 * @param suppressionReleaseMillis quiet time required after a burst before onsets
 *   are trusted again.
 */
data class ClapProfile(
    val minPeakAmplitude: Float = 0.08f,
    val minAmbientRatio: Float = 6f,
    val minAttackRatio: Float = 4f,
    // Kept loose on purpose. Room reverberation fills in the gaps between a clap's
    // peak and its average, pushing crest down, so this is a coarse "is it
    // impulsive at all" check — sustained tones measure 1.5–2.7 — while
    // minHighFrequencyRatio does the real discriminating.
    val minCrestFactor: Float = 2.5f,
    val minHighFrequencyRatio: Float = 0.12f,
    val maxTransientMillis: Long = 120L,
    val quietBeforeMillis: Long = 96L,
    val quietAfterMillis: Long = 48L,
    val quietAmbientRatio: Float = 2.5f,
    val noiseFloorFallMillis: Long = 200L,
    val noiseFloorRiseMillis: Long = 3_000L,
    val warmUpMillis: Long = 300L,
    val adaptiveAmbientMargin: Float = 4f,
    val adaptiveRangeUp: Float = 4f,
    val adaptiveRangeDown: Float = 1f,
    val maxTransientsPerWindow: Int = 4,
    val transientWindowMillis: Long = 2_000L,
    val suppressionReleaseMillis: Long = 1_000L,
) {
    init {
        require(adaptiveRangeUp >= 1f) { "adaptiveRangeUp cannot shrink the gate" }
        require(adaptiveRangeDown >= 1f) { "adaptiveRangeDown is a divisor, so >= 1" }
        require(maxTransientsPerWindow >= MIN_TRANSIENT_ALLOWANCE) {
            "a triple clap is three onsets, so the allowance must exceed it"
        }
    }

    /**
     * The absolute peak gate for a frame, given how loud the room currently is.
     *
     * Rises with the ambient peak so a busier room demands a louder clap, but only
     * within [adaptiveRangeUp]. Everything else about detection stays fixed, which
     * is what keeps this from behaving like an auto-sensitivity control that hunts.
     */
    fun adaptiveMinPeak(ambientPeak: Float): Float {
        val floor = minPeakAmplitude / adaptiveRangeDown
        val ceiling = minPeakAmplitude * adaptiveRangeUp
        return (ambientPeak * adaptiveAmbientMargin).coerceIn(floor, ceiling)
    }

    private companion object {
        /** Three claps in quick succession must never count as a burst. */
        const val MIN_TRANSIENT_ALLOWANCE = 4
    }
}

/**
 * Applies sensitivity to the loudness gates.
 *
 * Sensitivity answers "how hard must I clap, and how close", not "what is a clap".
 * Loosening the character gates as well would simply let doors and speech through,
 * so [ClapProfile.minCrestFactor], [ClapProfile.minHighFrequencyRatio] and the
 * timing windows are left alone.
 *
 * The factor is geometric around the midpoint: 0f gives thresholds 2.5x stricter
 * than default, 0.5f leaves them unchanged, 1f loosens them by the same ratio.
 */
internal fun ClapProfile.scaledBy(sensitivity: Float): ClapProfile {
    val clamped = sensitivity.coerceIn(0f, 1f)
    val factor = SENSITIVITY_SPAN.pow(1f - 2f * clamped)
    return copy(
        minPeakAmplitude = (minPeakAmplitude * factor).coerceIn(MIN_PEAK_FLOOR, MIN_PEAK_CEILING),
        minAmbientRatio = maxOf(MIN_RATIO, minAmbientRatio * factor),
        minAttackRatio = maxOf(MIN_RATIO, minAttackRatio * factor),
    )
}

private const val SENSITIVITY_SPAN = 2.5f
private const val MIN_PEAK_FLOOR = 0.004f
private const val MIN_PEAK_CEILING = 0.9f
private const val MIN_RATIO = 1.5f
