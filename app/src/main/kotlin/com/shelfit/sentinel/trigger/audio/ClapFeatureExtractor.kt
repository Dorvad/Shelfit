package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Scalar description of one audio frame. This is all that survives analysis — the
 * PCM buffer it came from is never retained.
 *
 * @param peak largest absolute sample, 0f..1f of full scale.
 * @param rms root-mean-square level of the frame, 0f..1f.
 * @param crestFactor [peak] / [rms]. High for impulses, low for sustained sound.
 * @param zeroCrossingRate sign changes per sample, 0f..1f. A coarse brightness cue.
 * @param highFrequencyRatio normalised energy of the first-difference signal,
 *   0f..1f. Approximates `sin²(pi * f / sampleRate)` for a tone at `f`, so it
 *   separates broadband claps from low-frequency thuds cheaply.
 * @param noiseFloor tracked background RMS at this point in the stream.
 * @param ambientRatio [rms] / [noiseFloor]. How far above the room this frame sits.
 * @param attackRatio [peak] divided by the previous frame's peak. The onset cue.
 * @param millisSinceStart position on the capture timeline, measured from the first
 *   frame this extractor saw.
 */
data class AudioFrameFeatures(
    val timestampMillis: Long,
    val millisSinceStart: Long,
    val peak: Float,
    val rms: Float,
    val crestFactor: Float,
    val zeroCrossingRate: Float,
    val highFrequencyRatio: Float,
    val noiseFloor: Float,
    val ambientRatio: Float,
    val attackRatio: Float,
)

/**
 * Reduces PCM frames to [AudioFrameFeatures] and tracks the room's background level.
 *
 * Deliberately free of Android imports and of any notion of what a clap is: it
 * measures, [ClapCandidateDetector] decides. One pass over the samples computes
 * every feature, so cost is a handful of multiply-accumulates per sample.
 *
 * Stateful across frames — it carries the noise floor, the previous frame's peak,
 * and the last sample for continuity of the difference filter — so one instance
 * belongs to one capture session. Call [reset] to reuse it.
 */
class ClapFeatureExtractor(
    private val audio: AudioCaptureConfig,
    private val profile: ClapProfile,
) {

    private var noiseFloor = 0f
    private var previousPeak = 0f
    private var previousSample = 0f
    private var firstTimestampMillis: Long? = null

    /**
     * Weight for the noise floor following the level down. Derived from the time
     * constant so that changing [AudioCaptureConfig.frameSamples] does not silently
     * change how fast the floor adapts.
     */
    private val fallCoefficient = coefficientFor(profile.noiseFloorFallMillis)
    private val riseCoefficient = coefficientFor(profile.noiseFloorRiseMillis)

    fun reset() {
        noiseFloor = 0f
        previousPeak = 0f
        previousSample = 0f
        firstTimestampMillis = null
    }

    fun extract(frame: AudioFrame): AudioFrameFeatures {
        val count = frame.sampleCount.coerceAtMost(frame.samples.size)
        val start = firstTimestampMillis ?: frame.startTimestampMillis.also {
            firstTimestampMillis = it
        }

        var peak = 0f
        var sumSquares = 0.0
        var differenceSumSquares = 0.0
        var zeroCrossings = 0
        var previous = previousSample

        for (index in 0 until count) {
            val sample = frame.samples[index] / FULL_SCALE

            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude

            sumSquares += (sample * sample).toDouble()

            val difference = sample - previous
            differenceSumSquares += (difference * difference).toDouble()

            if ((sample >= 0f) != (previous >= 0f)) zeroCrossings++

            previous = sample
        }
        previousSample = previous

        val rms = if (count > 0) sqrt(sumSquares / count).toFloat() else 0f

        // A signal alternating at Nyquist has difference energy 4x its own, so
        // dividing by four maps the ratio onto 0f..1f.
        val highFrequencyRatio = if (sumSquares > 0.0) {
            (differenceSumSquares / (NYQUIST_DIFFERENCE_GAIN * sumSquares)).toFloat()
                .coerceIn(0f, 1f)
        } else {
            0f
        }

        val crestFactor = if (rms > EPSILON) peak / rms else 0f
        val zeroCrossingRate = if (count > 1) zeroCrossings.toFloat() / (count - 1) else 0f
        val attackRatio = peak / maxOf(previousPeak, ATTACK_REFERENCE_FLOOR)

        updateNoiseFloor(rms)
        val ambientRatio = rms / maxOf(noiseFloor, EPSILON)

        previousPeak = peak

        return AudioFrameFeatures(
            timestampMillis = frame.startTimestampMillis,
            millisSinceStart = frame.startTimestampMillis - start,
            peak = peak,
            rms = rms,
            crestFactor = crestFactor,
            zeroCrossingRate = zeroCrossingRate,
            highFrequencyRatio = highFrequencyRatio,
            noiseFloor = noiseFloor,
            ambientRatio = ambientRatio,
            attackRatio = attackRatio,
        )
    }

    /**
     * Follows the background level: quickly downwards, slowly upwards.
     *
     * The asymmetry is the whole trick. Rising slowly means a clap — loud but brief
     * — leaves the floor essentially untouched, so it still stands out as a
     * transient. Music or a running tap, loud for seconds, does pull the floor up,
     * after which it correctly stops looking like a transient.
     */
    private fun updateNoiseFloor(rms: Float) {
        val floor = noiseFloor
        if (floor <= 0f) {
            noiseFloor = rms
            return
        }
        val coefficient = if (rms < floor) fallCoefficient else riseCoefficient
        noiseFloor = floor + (rms - floor) * coefficient
    }

    private fun coefficientFor(timeConstantMillis: Long): Float {
        if (timeConstantMillis <= 0L) return 1f
        val frameMillis = audio.frameDurationMillis.coerceAtLeast(1L)
        return 1f - exp(-frameMillis.toFloat() / timeConstantMillis.toFloat())
    }

    private companion object {
        const val FULL_SCALE = 32_768f
        const val EPSILON = 1e-7f

        /** Difference-signal energy gain for a full-scale Nyquist tone. */
        const val NYQUIST_DIFFERENCE_GAIN = 4.0

        /**
         * Lower bound on the previous peak when computing attack. Without it, any
         * sound following true digital silence would show infinite attack.
         */
        const val ATTACK_REFERENCE_FLOOR = 0.002f
    }
}
