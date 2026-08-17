package com.shelfit.sentinel.trigger.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning measurements into thresholds. Pure arithmetic, so these tests state the
 * intent of the derivation directly rather than inferring it from behaviour.
 */
class ClapCalibrationTest {

    private fun calibration(
        ambientRms: Float = 0.002f,
        ambientPeak: Float = 0.01f,
        clapPeakMedian: Float = 0.40f,
        clapPeakMinimum: Float = 0.30f,
        ambientRatioMinimum: Float = 60f,
        crestMinimum: Float = 3.4f,
        highFrequencyMinimum: Float = 0.5f,
        transientMaximumMillis: Long = 64L,
        sampleCount: Int = 5,
    ) = ClapCalibration(
        capturedAtEpochMillis = 1_000L,
        sampleCount = sampleCount,
        ambientRms = ambientRms,
        ambientPeak = ambientPeak,
        clapPeakMedian = clapPeakMedian,
        clapPeakMinimum = clapPeakMinimum,
        clapAmbientRatioMinimum = ambientRatioMinimum,
        clapCrestFactorMinimum = crestMinimum,
        clapHighFrequencyRatioMinimum = highFrequencyMinimum,
        clapTransientMaximumMillis = transientMaximumMillis,
    )

    @Test
    fun `the peak gate sits between the room and the softest clap`() {
        val calibration = calibration(ambientPeak = 0.01f, clapPeakMinimum = 0.30f)

        val profile = calibration.toProfile()

        assertTrue(
            "gate ${profile.minPeakAmplitude} must clear the room's peaks",
            profile.minPeakAmplitude > calibration.ambientPeak,
        )
        assertTrue(
            "gate ${profile.minPeakAmplitude} must sit below the softest clap",
            profile.minPeakAmplitude < calibration.clapPeakMinimum,
        )
    }

    @Test
    fun `a quiet room produces a lower gate than a noisy one`() {
        val quiet = calibration(ambientPeak = 0.004f).toProfile().minPeakAmplitude
        val noisy = calibration(ambientPeak = 0.05f).toProfile().minPeakAmplitude

        assertTrue("quiet $quiet should be below noisy $noisy", quiet < noisy)
    }

    @Test
    fun `a quiet microphone produces a lower gate than a hot one`() {
        // Same room, but one device records claps at half the level of the other.
        val quietMic = calibration(clapPeakMedian = 0.2f, clapPeakMinimum = 0.15f)
        val hotMic = calibration(clapPeakMedian = 0.8f, clapPeakMinimum = 0.6f)

        assertTrue(
            quietMic.toProfile().minPeakAmplitude < hotMic.toProfile().minPeakAmplitude,
        )
    }

    @Test
    fun `a reverberant room widens the decay window`() {
        val dry = calibration(transientMaximumMillis = 48L).toProfile()
        val live = calibration(transientMaximumMillis = 240L).toProfile()

        assertTrue(
            "a live room must be allowed a longer decay: dry " +
                "${dry.maxTransientMillis} vs live ${live.maxTransientMillis}",
            live.maxTransientMillis > dry.maxTransientMillis,
        )
        assertTrue(
            "and it must exceed the observed decay, not clip it",
            live.maxTransientMillis > 240L,
        )
    }

    @Test
    fun `a bright microphone ends up stricter than the generic default`() {
        val base = ClapProfile()
        val bright = calibration(highFrequencyMinimum = 0.6f).toProfile()

        assertTrue(
            "measuring bright claps should tighten the spectral gate, was " +
                bright.minHighFrequencyRatio,
            bright.minHighFrequencyRatio > base.minHighFrequencyRatio,
        )
    }

    @Test
    fun `a dull microphone ends up looser so claps still register`() {
        val base = ClapProfile()
        val dull = calibration(highFrequencyMinimum = 0.14f).toProfile()

        assertTrue(
            "was ${dull.minHighFrequencyRatio}",
            dull.minHighFrequencyRatio < base.minHighFrequencyRatio,
        )
    }

    @Test
    fun `the crest gate is only ever loosened, never tightened`() {
        val base = ClapProfile()
        val veryImpulsive = calibration(crestMinimum = 12f).toProfile()
        val barelyImpulsive = calibration(crestMinimum = 2.4f).toProfile()

        assertEquals(
            "five samples is not evidence enough to demand more crest",
            base.minCrestFactor,
            veryImpulsive.minCrestFactor,
            1e-6f,
        )
        assertTrue(barelyImpulsive.minCrestFactor < base.minCrestFactor)
    }

    @Test
    fun `the attack gate is left alone because it varies too much`() {
        val base = ClapProfile()

        assertEquals(base.minAttackRatio, calibration().toProfile().minAttackRatio, 1e-6f)
    }

    @Test
    fun `every derived threshold stays inside sane bounds`() {
        // Absurd measurements must not produce an unusable configuration.
        val extreme = calibration(
            ambientPeak = 0.9f,
            clapPeakMedian = 0.95f,
            clapPeakMinimum = 0.95f,
            ambientRatioMinimum = 5_000f,
            crestMinimum = 0.01f,
            highFrequencyMinimum = 0.99f,
            transientMaximumMillis = 10_000L,
        )

        val profile = extreme.toProfile()

        assertTrue(profile.minPeakAmplitude in 0.004f..0.9f)
        assertTrue(profile.minAmbientRatio in 3f..40f)
        assertTrue(profile.minHighFrequencyRatio in 0.04f..0.35f)
        assertTrue(profile.minCrestFactor >= 1.6f)
        assertTrue(profile.maxTransientMillis in 80L..400L)
    }

    // ---- Quality ------------------------------------------------------------

    @Test
    fun `good separation is reported when claps stand well clear of the room`() {
        val calibration = calibration(ambientPeak = 0.01f, clapPeakMedian = 0.4f)

        assertEquals(CalibrationQuality.GOOD, calibration.quality)
        assertTrue(calibration.headroomDecibels > 20f)
    }

    @Test
    fun `poor separation is reported when the room is nearly as loud as the claps`() {
        val calibration = calibration(ambientPeak = 0.1f, clapPeakMedian = 0.3f)

        assertEquals(CalibrationQuality.POOR, calibration.quality)
    }

    @Test
    fun `too few claps is poor regardless of separation`() {
        val calibration = calibration(sampleCount = 2)

        assertEquals(CalibrationQuality.POOR, calibration.quality)
    }

    @Test
    fun `fewer claps than asked for is marginal rather than good`() {
        val calibration = calibration(sampleCount = 3)

        assertEquals(CalibrationQuality.MARGINAL, calibration.quality)
    }

    // ---- Statistics ---------------------------------------------------------

    @Test
    fun `the median ignores one odd clap while the extremes stay safe`() {
        val claps = listOf(0.4f, 0.42f, 0.38f, 0.41f, 0.95f).map { peak ->
            ClapCandidate(
                onsetMillis = 0L,
                confidence = 0.8f,
                peak = peak,
                ambientRatio = 50f,
                transientMillis = 48L,
                crestFactor = 3.5f,
                highFrequencyRatio = 0.5f,
            )
        }

        val calibration = ClapCalibration.from(
            capturedAtEpochMillis = 0L,
            ambient = AmbientMeasurement(meanRms = 0.002f, peak = 0.01f, frameCount = 100),
            claps = claps,
        )

        assertEquals("median should ignore the outlier", 0.41f, calibration.clapPeakMedian, 1e-6f)
        assertEquals(
            "the minimum defines the threshold, so it must be the true minimum",
            0.38f,
            calibration.clapPeakMinimum,
            1e-6f,
        )
    }

    @Test
    fun `an even number of claps averages the middle two`() {
        val claps = listOf(0.2f, 0.4f, 0.6f, 0.8f).map { peak ->
            ClapCandidate(0L, 0.8f, peak, 50f, 48L, 3.5f, 0.5f)
        }

        val calibration = ClapCalibration.from(
            capturedAtEpochMillis = 0L,
            ambient = AmbientMeasurement(0.002f, 0.01f, 100),
            claps = claps,
        )

        assertEquals(0.5f, calibration.clapPeakMedian, 1e-6f)
    }
}
