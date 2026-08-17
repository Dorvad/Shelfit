package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.FakeSensorStatusProvider
import com.shelfit.sentinel.core.MonotonicClock
import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioFrame
import com.shelfit.sentinel.core.audio.AudioInput
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reliability mechanisms added on top of the feature gates: the adaptive
 * absolute threshold, and burst suppression.
 *
 * Both are about a room that changes rather than a sound in isolation, so these tests
 * build signals whose *environment* moves partway through.
 */
class AdaptiveDetectionTest {

    private val audio = AudioCaptureConfig()
    private val profile = ClapProfile()
    private val configuration = DoubleClapConfiguration()

    private fun detector(input: AudioInput) = DoubleClapDetector(
        audioInput = input,
        sensorStatus = FakeSensorStatusProvider(
            mapOf(SensorKind.MICROPHONE to SensorAvailability.AVAILABLE),
        ),
        clock = MonotonicClock { 0L },
    )

    private fun signal() = SyntheticSignal(audio)

    /** Runs the analysis chain directly, so the gate and suppression can be inspected. */
    private fun analyse(signal: SyntheticSignal): Trace {
        val samples = signal.build()
        val extractor = ClapFeatureExtractor(audio, profile)
        val candidates = ClapCandidateDetector(audio, profile)
        val trace = Trace()
        var position = 0
        while (position + audio.frameSamples <= samples.size) {
            val features = extractor.extract(
                AudioFrame(
                    samples = samples.copyOfRange(position, position + audio.frameSamples),
                    sampleCount = audio.frameSamples,
                    startTimestampMillis = position * 1_000L / audio.sampleRateHz,
                ),
            )
            when (val detection = candidates.onFrame(features)) {
                is ClapDetection.Candidate -> trace.accepted++
                is ClapDetection.Rejected -> trace.rejections += detection.reason
                ClapDetection.None -> Unit
            }
            trace.maxGate = maxOf(trace.maxGate, candidates.effectiveMinPeak)
            trace.minGate = minOf(trace.minGate, candidates.effectiveMinPeak)
            if (candidates.suppressingBurst) trace.suppressed = true
            position += audio.frameSamples
        }
        return trace
    }

    private class Trace {
        var accepted = 0
        val rejections = mutableSetOf<ClapRejection>()
        var maxGate = 0f
        var minGate = Float.MAX_VALUE
        var suppressed = false
    }

    // ---- Adaptive threshold -------------------------------------------------

    @Test
    fun `the gate stays at the configured level in a quiet room`() {
        val trace = analyse(signal().silence(2_000L))

        assertEquals(profile.minPeakAmplitude, trace.maxGate, 1e-6f)
    }

    @Test
    fun `the gate rises when the room gets louder`() {
        val trace = analyse(
            signal()
                .silence(700L)
                .room(0.05f)
                .silence(6_000L),
        )

        assertTrue(
            "gate should have risen above the configured level, reached ${trace.maxGate}",
            trace.maxGate > profile.minPeakAmplitude * 1.5f,
        )
    }

    @Test
    fun `the gate never rises beyond its configured ceiling`() {
        // A very loud room. Without a bound the gate would chase it until nothing
        // could ever be heard.
        val trace = analyse(
            signal()
                .silence(700L)
                .room(0.5f)
                .silence(8_000L),
        )

        assertTrue(
            "gate reached ${trace.maxGate}, ceiling is " +
                "${profile.minPeakAmplitude * profile.adaptiveRangeUp}",
            trace.maxGate <= profile.minPeakAmplitude * profile.adaptiveRangeUp + 1e-6f,
        )
    }

    @Test
    fun `the gate never falls below the configured level`() {
        // A quieter room is handled by the ratio gate, not by lowering this one.
        val trace = analyse(signal().silence(700L).room(0.00002f).silence(4_000L))

        assertTrue(
            "gate fell to ${trace.minGate}",
            trace.minGate >= profile.minPeakAmplitude - 1e-6f,
        )
    }

    @Test
    fun `a clap that worked in a quiet room is rejected once the room is loud`() = runTest {
        val quiet = signal()
            .silence(700L)
            .clap(amplitude = 0.12f)
            .silence(220L)
            .clap(amplitude = 0.12f)
            .silence(500L)
            .toAudioInput()

        val loud = signal()
            .silence(700L)
            .room(0.06f)
            .silence(6_000L)
            .clap(amplitude = 0.12f)
            .silence(220L)
            .clap(amplitude = 0.12f)
            .silence(500L)
            .toAudioInput()

        assertEquals(
            "the same clap is heard in a quiet room",
            1,
            detector(quiet).events(configuration).toList().size,
        )
        assertTrue(
            "once the room is loud, that clap no longer stands out enough",
            detector(loud).events(configuration).toList().isEmpty(),
        )
    }

    @Test
    fun `a louder clap is still heard in a loud room`() = runTest {
        // A busy room rather than a deafening one. At 0.06 even a full-scale clap is
        // barely six times the noise floor in RMS terms, which is a real limit of
        // acoustics rather than of this detector — calibration reports it as poor
        // headroom instead of pretending a threshold would fix it.
        val input = signal()
            .silence(700L)
            .room(0.03f)
            .silence(6_000L)
            .clap(amplitude = 0.7f)
            .silence(220L)
            .clap(amplitude = 0.7f)
            .silence(500L)
            .toAudioInput()

        assertEquals(
            "adaptation must raise the bar, not make the detector deaf",
            1,
            detector(input).events(configuration).toList().size,
        )
    }

    @Test
    fun `detection recovers once a noisy room falls quiet again`() = runTest {
        val input = signal()
            .silence(700L)
            .room(0.06f)
            .silence(5_000L)
            .room(0.0005f)
            .silence(3_000L)
            .clap(amplitude = 0.12f)
            .silence(220L)
            .clap(amplitude = 0.12f)
            .silence(500L)
            .toAudioInput()

        assertEquals(
            "the background falls quickly, so sensitivity should come back",
            1,
            detector(input).events(configuration).toList().size,
        )
    }

    // ---- Burst suppression -------------------------------------------------

    @Test
    fun `a rapid run of clap-like impulses engages suppression`() {
        val trace = analyse(
            signal()
                .silence(700L)
                .impulseTrain(count = 12, intervalMillis = 200L)
                .silence(500L),
        )

        assertTrue("suppression should have engaged", trace.suppressed)
        assertTrue(ClapRejection.TOO_MANY_TRANSIENTS in trace.rejections)
        assertTrue(
            "most of the burst should be rejected, ${trace.accepted} were accepted",
            trace.accepted <= profile.maxTransientsPerWindow,
        )
    }

    @Test
    fun `sustained hammering fires once rather than repeatedly`() = runTest {
        // Roughly five strikes a second for six seconds. Without suppression the
        // cooldown alone would allow a detection every 1.5 s.
        val input = signal()
            .silence(700L)
            .impulseTrain(count = 30, intervalMillis = 200L)
            .silence(500L)
            .toAudioInput()

        val events = detector(input).events(configuration).toList()

        assertTrue(
            "expected at most one false detection across the whole burst, got " +
                events.size,
            events.size <= 1,
        )
    }

    @Test
    fun `a dense rattle is suppressed before any pair can complete`() = runTest {
        // Fast enough that the onset allowance is exhausted inside the gesture window.
        val input = signal()
            .silence(700L)
            .impulseTrain(count = 20, intervalMillis = 60L)
            .silence(500L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `a genuine double clap is well inside the burst allowance`() {
        val trace = analyse(
            signal().silence(700L).clap().silence(220L).clap().silence(500L),
        )

        assertEquals(2, trace.accepted)
        assertTrue("two claps must never look like a burst", !trace.suppressed)
    }

    @Test
    fun `a triple clap is still inside the burst allowance`() {
        val trace = analyse(
            signal()
                .silence(700L)
                .clap()
                .silence(220L)
                .clap()
                .silence(220L)
                .clap()
                .silence(500L),
        )

        assertEquals(3, trace.accepted)
        assertTrue("three claps must not trip suppression", !trace.suppressed)
    }

    @Test
    fun `suppression lifts once the room settles`() {
        val trace = analyse(
            signal()
                .silence(700L)
                .impulseTrain(count = 12, intervalMillis = 200L)
                // Longer than transientWindowMillis plus suppressionReleaseMillis.
                .silence(4_000L)
                .clap()
                .silence(220L)
                .clap()
                .silence(500L),
        )

        assertTrue(ClapRejection.TOO_MANY_TRANSIENTS in trace.rejections)
        assertTrue(
            "the pair after the burst should be accepted again",
            trace.accepted >= 2,
        )
    }
}
