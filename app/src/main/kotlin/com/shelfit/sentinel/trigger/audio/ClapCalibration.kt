package com.shelfit.sentinel.trigger.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * What calibration measured, and nothing else.
 *
 * These are the only values that survive a calibration run. There is no field here
 * that could hold audio: every number is a level, a ratio or a duration derived from
 * frames that were discarded as they were analysed.
 *
 * Measurements are stored rather than the thresholds computed from them, so that
 * [toProfile] stays the single definition of how a room becomes a configuration. A
 * later improvement to the derivation then applies to everyone who has calibrated,
 * instead of leaving them on values computed by an older formula.
 *
 * @param ambientRms mean frame RMS across the ambient measurement.
 * @param ambientPeak a high percentile of frame peaks during the ambient
 *   measurement — the level the room actually reaches, rather than its average.
 * @param clapPeakMedian typical peak of the collected claps.
 * @param clapPeakMinimum the softest clap collected. Thresholds are set below this,
 *   since a user's real claps will sometimes be softer than any they demonstrated.
 * @param clapTransientMaximumMillis longest observed decay back to background —
 *   effectively a measurement of the room's reverberation.
 */
data class ClapCalibration(
    val capturedAtEpochMillis: Long,
    val sampleCount: Int,
    val ambientRms: Float,
    val ambientPeak: Float,
    val clapPeakMedian: Float,
    val clapPeakMinimum: Float,
    val clapAmbientRatioMinimum: Float,
    val clapCrestFactorMinimum: Float,
    val clapHighFrequencyRatioMinimum: Float,
    val clapTransientMaximumMillis: Long,
) {

    /**
     * How far the user's claps stand above their room, as a plain ratio.
     *
     * The single most useful number in the whole calibration: if this is small, no
     * choice of threshold will work well and the honest answer is to say so.
     */
    val headroom: Float get() = clapPeakMedian / maxOf(ambientPeak, EPSILON)

    val headroomDecibels: Float get() = 20f * log10(maxOf(headroom, EPSILON))

    val quality: CalibrationQuality
        get() = when {
            sampleCount < MINIMUM_USABLE_SAMPLES -> CalibrationQuality.POOR
            headroom < POOR_HEADROOM -> CalibrationQuality.POOR
            headroom < GOOD_HEADROOM -> CalibrationQuality.MARGINAL
            sampleCount < PREFERRED_SAMPLES -> CalibrationQuality.MARGINAL
            else -> CalibrationQuality.GOOD
        }

    /**
     * Turns measurements into thresholds.
     *
     * Five parameters are derived, chosen because they are the ones that genuinely
     * vary between a phone on a kitchen counter and a phone in a carpeted bedroom:
     *
     *  - **minPeakAmplitude** is placed in the gap between the loudest the room gets
     *    and the softest clap observed, at the geometric midpoint — the middle of the
     *    gap measured in decibels, which is how loudness actually behaves. If there
     *    is no gap, hearing claps is prioritised and [quality] reports the problem
     *    rather than silently shipping a threshold that cannot work.
     *  - **minAmbientRatio** comes from how far the softest clap stood above the
     *    noise floor, halved for margin.
     *  - **minHighFrequencyRatio** tracks the microphone's high-frequency response.
     *    A bright microphone ends up *stricter* than the generic default, which
     *    improves rejection of thuds; a dull one ends up looser so claps still
     *    register.
     *  - **maxTransientMillis** is derived from the longest observed decay, which is
     *    a direct measurement of the room's reverberation. This is what stops a tiled
     *    bathroom rejecting every clap for "lasting too long".
     *  - **minCrestFactor** is only ever loosened, never tightened. Crest is the
     *    least reliable of the features and five samples is not enough evidence to
     *    justify demanding more of it.
     *
     * [ClapProfile.minAttackRatio] is deliberately left alone: it is measured against
     * whatever the previous frame happened to contain, so it varies far too much
     * between individual claps to fit on a handful of examples.
     */
    fun toProfile(base: ClapProfile = ClapProfile()): ClapProfile {
        val fromAmbient = ambientPeak * AMBIENT_HEADROOM_MARGIN
        val fromClaps = clapPeakMinimum * SOFTEST_CLAP_MARGIN

        val minPeak = if (fromAmbient < fromClaps) {
            sqrt(fromAmbient * fromClaps)
        } else {
            // The room reaches clap level. Favour hearing the user; quality says POOR.
            fromClaps
        }

        return base.copy(
            minPeakAmplitude = minPeak.coerceIn(MIN_PEAK_FLOOR, MIN_PEAK_CEILING),
            minAmbientRatio = (clapAmbientRatioMinimum * AMBIENT_RATIO_MARGIN)
                .coerceIn(MIN_AMBIENT_RATIO, MAX_AMBIENT_RATIO),
            minHighFrequencyRatio = (clapHighFrequencyRatioMinimum * HIGH_FREQUENCY_MARGIN)
                .coerceIn(MIN_HIGH_FREQUENCY, MAX_HIGH_FREQUENCY),
            minCrestFactor = minOf(
                base.minCrestFactor,
                clapCrestFactorMinimum * CREST_MARGIN,
            ).coerceAtLeast(MIN_CREST),
            maxTransientMillis = (clapTransientMaximumMillis * REVERB_MARGIN_NUMERATOR /
                REVERB_MARGIN_DENOMINATOR + REVERB_MARGIN_MILLIS)
                .coerceIn(MIN_TRANSIENT_WINDOW, MAX_TRANSIENT_WINDOW),
        )
    }

    companion object {
        /** Claps to ask for. More would be tedious; fewer makes the statistics noise. */
        const val PREFERRED_SAMPLES = 5

        /** Below this a run is not worth keeping. */
        const val MINIMUM_USABLE_SAMPLES = 3

        private const val EPSILON = 1e-6f

        private const val POOR_HEADROOM = 6f
        private const val GOOD_HEADROOM = 12f

        /** Threshold must clear the room's peaks by this much. */
        private const val AMBIENT_HEADROOM_MARGIN = 4f

        /** Threshold must sit this far below the softest clap demonstrated. */
        private const val SOFTEST_CLAP_MARGIN = 0.5f

        private const val AMBIENT_RATIO_MARGIN = 0.5f
        private const val HIGH_FREQUENCY_MARGIN = 0.6f
        private const val CREST_MARGIN = 0.8f

        private const val REVERB_MARGIN_NUMERATOR = 3L
        private const val REVERB_MARGIN_DENOMINATOR = 2L
        private const val REVERB_MARGIN_MILLIS = 32L

        private const val MIN_PEAK_FLOOR = 0.004f
        private const val MIN_PEAK_CEILING = 0.9f
        private const val MIN_AMBIENT_RATIO = 3f
        private const val MAX_AMBIENT_RATIO = 40f
        private const val MIN_HIGH_FREQUENCY = 0.04f
        private const val MAX_HIGH_FREQUENCY = 0.35f
        private const val MIN_CREST = 1.6f
        private const val MIN_TRANSIENT_WINDOW = 80L
        private const val MAX_TRANSIENT_WINDOW = 400L

        /**
         * Builds a calibration from what was observed.
         *
         * Uses the median for the typical clap and the extremes for the thresholds,
         * because a threshold has to accommodate the user's worst clap rather than
         * their average one. Robust to a single odd sample by construction — the
         * median ignores it, and the extremes are pulled towards safety, not away
         * from it.
         */
        fun from(
            capturedAtEpochMillis: Long,
            ambient: AmbientMeasurement,
            claps: List<ClapCandidate>,
        ): ClapCalibration {
            require(claps.isNotEmpty()) { "calibration needs at least one clap" }
            return ClapCalibration(
                capturedAtEpochMillis = capturedAtEpochMillis,
                sampleCount = claps.size,
                ambientRms = ambient.meanRms,
                ambientPeak = ambient.peak,
                clapPeakMedian = claps.map { it.peak }.median(),
                clapPeakMinimum = claps.minOf { it.peak },
                clapAmbientRatioMinimum = claps.minOf { it.ambientRatio },
                clapCrestFactorMinimum = claps.minOf { it.crestFactor },
                clapHighFrequencyRatioMinimum = claps.minOf { it.highFrequencyRatio },
                clapTransientMaximumMillis = claps.maxOf { it.transientMillis },
            )
        }

        private fun List<Float>.median(): Float {
            val sorted = sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                (sorted[middle - 1] + sorted[middle]) / 2f
            }
        }
    }
}

/** Summary of the room, taken before any clapping. */
data class AmbientMeasurement(
    val meanRms: Float,
    /** High percentile of frame peaks, not the maximum — one cough must not define a room. */
    val peak: Float,
    val frameCount: Int,
)

enum class CalibrationQuality {
    /** Claps stand well clear of the room. Detection should be reliable. */
    GOOD,

    /** Usable, but the margin is thin. Expect occasional misses or false triggers. */
    MARGINAL,

    /** The room is too loud relative to the claps, or too few claps were heard. */
    POOR,
}

/**
 * A deliberately permissive profile, used only while *collecting* clap examples.
 *
 * Calibration has a chicken-and-egg problem: it needs to detect claps in order to
 * measure them, but the thresholds for detecting them are what it is trying to
 * establish. The way out is to collect with gates loose enough that almost any clap
 * registers — anchored to the ambient level just measured, so it adapts to the device
 * immediately — and then derive strict thresholds from what was actually observed.
 *
 * Loose gates mean an odd sound can be collected as a clap. That is handled
 * statistically rather than by tightening: [ClapCalibration.from] uses the median for
 * the typical clap, and the user reviews and tests the result before it is saved.
 */
internal fun ClapProfile.forCalibrationCapture(ambientPeak: Float): ClapProfile = copy(
    minPeakAmplitude = maxOf(CAPTURE_PEAK_FLOOR, ambientPeak * CAPTURE_AMBIENT_MARGIN),
    minAmbientRatio = CAPTURE_AMBIENT_RATIO,
    minAttackRatio = CAPTURE_ATTACK_RATIO,
    minCrestFactor = CAPTURE_CREST,
    minHighFrequencyRatio = CAPTURE_HIGH_FREQUENCY,
    maxTransientMillis = CAPTURE_TRANSIENT_WINDOW,
    // The ambient phase has already warmed the noise floor.
    warmUpMillis = 0L,
    // Someone demonstrating claps produces a burst by definition.
    maxTransientsPerWindow = CAPTURE_TRANSIENT_ALLOWANCE,
    // Never let the adaptive gate move during collection: the point is to observe
    // claps against one fixed reference.
    adaptiveAmbientMargin = 0f,
)

private const val CAPTURE_PEAK_FLOOR = 0.01f
private const val CAPTURE_AMBIENT_MARGIN = 3f
private const val CAPTURE_AMBIENT_RATIO = 3f
private const val CAPTURE_ATTACK_RATIO = 2f
private const val CAPTURE_CREST = 1.8f
private const val CAPTURE_HIGH_FREQUENCY = 0.03f
private const val CAPTURE_TRANSIENT_WINDOW = 400L
private const val CAPTURE_TRANSIENT_ALLOWANCE = 64
