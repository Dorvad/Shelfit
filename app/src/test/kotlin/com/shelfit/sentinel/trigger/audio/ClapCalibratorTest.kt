package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.FakeSensorStatusProvider
import com.shelfit.sentinel.core.MonotonicClock
import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full calibration run over synthetic audio: measure a room, collect claps, derive
 * thresholds.
 *
 * The most valuable test here is the round trip — calibrate on one signal, then detect
 * with the derived profile on a comparable one. That is the property that matters and
 * it cannot be established by checking the arithmetic alone.
 */
class ClapCalibratorTest {

    private val audio = AudioCaptureConfig()

    private val spec = CalibrationSpec(
        ambientMillis = 600L,
        clapTimeoutMillis = 8_000L,
        audio = audio,
    )

    private fun signal(background: Float = 0.0005f) =
        SyntheticSignal(audio, backgroundAmplitude = background)

    /** A calibration session: quiet room, then five demonstration claps. */
    private fun demonstration(
        background: Float = 0.0005f,
        clapAmplitude: Float = 0.4f,
        decayMillis: Float = 6f,
        clapDurationMillis: Long = 80L,
        claps: Int = 5,
    ) = signal(background).apply {
        silence(spec.ambientMillis + 200L)
        repeat(claps) {
            clap(
                amplitude = clapAmplitude,
                decayMillis = decayMillis,
                durationMillis = clapDurationMillis,
            )
            // Scaled with the clap: a reverberant clap needs longer to settle before
            // the next one can satisfy the quiet-before test.
            silence(600L + clapDurationMillis)
        }
        silence(400L)
    }

    private suspend fun run(signal: SyntheticSignal): List<CalibrationStage> =
        ClapCalibrator(signal.toAudioInput()).run(spec, capturedAtEpochMillis = 42L).toList()

    private suspend fun complete(signal: SyntheticSignal): CalibrationStage.Complete {
        val stages = run(signal)
        return stages.last() as? CalibrationStage.Complete
            ?: error("expected completion, got ${stages.last()}")
    }

    @Test
    fun `a run measures the room then collects the requested claps`() = runTest {
        val stages = run(demonstration())

        assertTrue(
            "should report ambient progress",
            stages.any { it is CalibrationStage.MeasuringAmbient },
        )
        assertTrue(
            "should report clap collection",
            stages.any { it is CalibrationStage.CollectingClaps },
        )

        val result = stages.last() as CalibrationStage.Complete
        assertEquals(spec.requiredClaps, result.calibration.sampleCount)
        assertEquals(42L, result.calibration.capturedAtEpochMillis)
    }

    @Test
    fun `ambient progress runs from zero to the full duration`() = runTest {
        val ambient = run(demonstration())
            .filterIsInstance<CalibrationStage.MeasuringAmbient>()

        assertTrue(ambient.isNotEmpty())
        assertTrue("fractions stay in range", ambient.all { it.fraction in 0f..1f })
        assertEquals("last frame completes the measurement", 1f, ambient.last().fraction, 0.05f)
    }

    @Test
    fun `the clap counter advances as claps arrive`() = runTest {
        val counts = run(demonstration())
            .filterIsInstance<CalibrationStage.CollectingClaps>()
            .map { it.collected }
            .distinct()

        assertEquals((0 until spec.requiredClaps).toList(), counts)
    }

    @Test
    fun `the measured room level reflects the actual background`() = runTest {
        val quiet = complete(demonstration(background = 0.0005f)).calibration
        val busy = complete(demonstration(background = 0.01f)).calibration

        assertTrue(
            "a busier room must measure higher: ${quiet.ambientRms} vs ${busy.ambientRms}",
            busy.ambientRms > quiet.ambientRms * 5f,
        )
    }

    @Test
    fun `the measured clap level reflects the actual clap`() = runTest {
        val soft = complete(demonstration(clapAmplitude = 0.15f)).calibration
        val loud = complete(demonstration(clapAmplitude = 0.7f)).calibration

        assertTrue(soft.clapPeakMedian < loud.clapPeakMedian)
    }

    @Test
    fun `a quiet room with clear claps is reported as good`() = runTest {
        val calibration = complete(demonstration()).calibration

        assertEquals(CalibrationQuality.GOOD, calibration.quality)
    }

    @Test
    fun `a busy room is not reported as good`() = runTest {
        val calibration = complete(
            demonstration(background = 0.03f, clapAmplitude = 0.25f),
        ).calibration

        assertTrue(
            "quality was ${calibration.quality} at " +
                "${calibration.headroomDecibels} dB headroom",
            calibration.quality != CalibrationQuality.GOOD,
        )
    }

    /**
     * In a genuinely noisy room the claps cannot even be collected, so the run fails
     * with something the user can act on rather than producing a configuration that
     * would never work. This is the honest outcome, and better than a POOR verdict.
     */
    @Test
    fun `a room too noisy to hear claps in fails with advice`() = runTest {
        val signal = demonstration(background = 0.09f, clapAmplitude = 0.35f)
        signal.silence(spec.clapTimeoutMillis)

        val result = run(signal).last()

        assertTrue("expected failure, got $result", result is CalibrationStage.Failed)
        assertTrue(
            (result as CalibrationStage.Failed).reason.contains("quieter"),
        )
    }

    @Test
    fun `a silent session fails rather than inventing a configuration`() = runTest {
        val stages = run(signal().silence(spec.ambientMillis + spec.clapTimeoutMillis + 500L))

        val failure = stages.last()
        assertTrue("expected failure, got $failure", failure is CalibrationStage.Failed)
        assertTrue(
            (failure as CalibrationStage.Failed).reason.contains("claps"),
        )
    }

    @Test
    fun `too few claps fails rather than calibrating on one example`() = runTest {
        val stages = run(demonstration(claps = 2).also { it.silence(spec.clapTimeoutMillis) })

        assertTrue(stages.last() is CalibrationStage.Failed)
    }

    @Test
    fun `enough claps but fewer than asked for still completes`() = runTest {
        val signal = demonstration(claps = ClapCalibration.MINIMUM_USABLE_SAMPLES)
        signal.silence(spec.clapTimeoutMillis)

        val result = run(signal).last()

        assertTrue("expected completion, got $result", result is CalibrationStage.Complete)
        assertEquals(
            ClapCalibration.MINIMUM_USABLE_SAMPLES,
            (result as CalibrationStage.Complete).calibration.sampleCount,
        )
    }

    @Test
    fun `a microphone failure is reported as a failed stage`() = runTest {
        val broken = object : com.shelfit.sentinel.core.audio.AudioInput {
            override fun frames(config: AudioCaptureConfig) =
                kotlinx.coroutines.flow.flow<com.shelfit.sentinel.core.audio.AudioFrame> {
                    throw com.shelfit.sentinel.core.audio.AudioInputUnavailableException(
                        "Microphone was taken by another app",
                    )
                }
        }

        val stages = ClapCalibrator(broken).run(spec, 0L).toList()

        assertEquals(
            CalibrationStage.Failed("Microphone was taken by another app"),
            stages.single(),
        )
    }

    // ---- Round trip ---------------------------------------------------------

    @Test
    fun `the derived profile detects the claps it was calibrated on`() = runTest {
        val result = complete(demonstration(clapAmplitude = 0.4f))

        val events = detect(result.profile, pair(amplitude = 0.4f))

        assertEquals("calibration must detect the user's own claps", 1, events)
    }

    @Test
    fun `a device that records quietly is calibrated to hear its own claps`() = runTest {
        // A phone whose microphone records claps at a tenth of full scale. The generic
        // 0.08 peak gate sits right at that level, so detection would be unreliable.
        val result = complete(demonstration(clapAmplitude = 0.09f))

        assertTrue(
            "the gate must drop below the generic default, was " +
                result.profile.minPeakAmplitude,
            result.profile.minPeakAmplitude < ClapProfile().minPeakAmplitude,
        )

        assertEquals(
            "the generic profile is unreliable at this level",
            0,
            detect(ClapProfile(), pair(amplitude = 0.09f)),
        )
        assertEquals(
            "the calibrated profile hears the same claps",
            1,
            detect(result.profile, pair(amplitude = 0.09f)),
        )
    }

    @Test
    fun `a reverberant room is calibrated so its long claps are accepted`() = runTest {
        // Claps that ring on for a few hundred milliseconds, as in a tiled bathroom.
        // The generic 120 ms decay window rejects these as lasting too long.
        val live = demonstration(decayMillis = 55f, clapDurationMillis = 320L)
        val result = complete(live)

        assertTrue(
            "the decay window must widen for a live room, was " +
                result.profile.maxTransientMillis,
            result.profile.maxTransientMillis > ClapProfile().maxTransientMillis,
        )

        fun reverberantPair() = pair(
            amplitude = 0.4f,
            decayMillis = 55f,
            durationMillis = 320L,
            gapMillis = 380L,
        )

        assertEquals(
            "the generic 120 ms decay window rejects these claps",
            0,
            detect(ClapProfile(), reverberantPair()),
        )
        assertEquals(
            "the calibrated window accommodates the room",
            1,
            detect(result.profile, reverberantPair()),
        )
    }

    /** A double clap for the detector to hear, matching a calibration scenario. */
    private fun pair(
        amplitude: Float,
        decayMillis: Float = 6f,
        durationMillis: Long = 80L,
        gapMillis: Long = 300L,
    ) = signal()
        .silence(700L)
        .clap(amplitude = amplitude, decayMillis = decayMillis, durationMillis = durationMillis)
        .silence(gapMillis - durationMillis)
        .clap(amplitude = amplitude, decayMillis = decayMillis, durationMillis = durationMillis)
        .silence(600L)

    /** Number of trigger events the detector produces for [signal] under [profile]. */
    private suspend fun detect(profile: ClapProfile, signal: SyntheticSignal): Int =
        DoubleClapDetector(
            audioInput = signal.toAudioInput(),
            sensorStatus = FakeSensorStatusProvider(
                mapOf(SensorKind.MICROPHONE to SensorAvailability.AVAILABLE),
            ),
            clock = MonotonicClock { 0L },
        ).events(DoubleClapConfiguration(profile = profile)).toList().size
}
