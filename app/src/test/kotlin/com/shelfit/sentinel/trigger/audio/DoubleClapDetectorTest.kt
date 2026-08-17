package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.FakeSensorStatusProvider
import com.shelfit.sentinel.core.MonotonicClock
import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioFrame
import com.shelfit.sentinel.core.audio.AudioInput
import com.shelfit.sentinel.core.audio.AudioInputUnavailableException
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole audio pipeline, end to end, on synthetic waveforms.
 *
 * This is where the false-trigger requirements are checked: each sound the detector
 * must not react to is generated, pushed through the real extractor, candidate
 * detector and state machine, and asserted to produce no [TriggerEvent].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DoubleClapDetectorTest {

    private val audio = AudioCaptureConfig()
    private val configuration = DoubleClapConfiguration()

    private val clock = MonotonicClock { FIXED_CLOCK_MILLIS }

    private fun detector(
        input: AudioInput,
        microphone: SensorAvailability = SensorAvailability.AVAILABLE,
    ) = DoubleClapDetector(
        audioInput = input,
        sensorStatus = FakeSensorStatusProvider(mapOf(SensorKind.MICROPHONE to microphone)),
        clock = clock,
    )

    private fun signal() = SyntheticSignal(audio)

    /** The gesture the app exists to hear: quiet room, clap, pause, clap. */
    private fun doubleClap(gapMillis: Long = 300L) = signal()
        .silence(WARM_UP_MILLIS)
        .clap()
        .silence(gapMillis - CLAP_LENGTH_MILLIS)
        .clap()
        .silence(500L)

    @Test
    fun `two claps produce one trigger event`() = runTest {
        val events = detector(doubleClap().toAudioInput())
            .events(configuration)
            .toList()

        assertEquals(1, events.size)
        assertEquals(TriggerId.DoubleClap, events.single().triggerId)
    }

    @Test
    fun `the event reports the gap between the two claps`() = runTest {
        val events = detector(doubleClap(gapMillis = 400L).toAudioInput())
            .events(configuration)
            .toList()

        val gap = events.single().detail.getValue("gapMillis").toLong()
        assertTrue(
            "gap should be close to the 400 ms in the signal, was $gap",
            gap in 350L..450L,
        )
    }

    @Test
    fun `the event carries a usable confidence and no audio`() = runTest {
        val event = detector(doubleClap().toAudioInput()).events(configuration).toList().single()

        assertTrue("confidence in range", event.confidence in 0.5f..1f)
        assertEquals(
            "detail should carry timing only, never sensor content",
            setOf("gapMillis"),
            event.detail.keys,
        )
        assertEquals(FIXED_CLOCK_MILLIS, event.elapsedRealtimeMillis)
    }

    @Test
    fun `claps are detected when they do not align with frame boundaries`() = runTest {
        // Real claps land anywhere within a frame. Offsets that are not multiples of
        // the 16 ms frame split a clap's energy across two frames.
        val offsets = listOf(3L, 7L, 11L, 13L)

        offsets.forEach { offset ->
            val input = signal()
                .silence(WARM_UP_MILLIS + offset)
                .clap()
                .silence(300L - CLAP_LENGTH_MILLIS)
                .clap()
                .silence(500L)
                .toAudioInput()

            assertEquals(
                "offset of $offset ms should still be heard",
                1,
                detector(input).events(configuration).toList().size,
            )
        }
    }

    @Test
    fun `a single clap produces nothing`() = runTest {
        val input = signal().silence(WARM_UP_MILLIS).clap().silence(1_500L).toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `two claps too far apart produce nothing`() = runTest {
        val input = doubleClap(gapMillis = 1_600L).toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `two claps too close together produce nothing`() = runTest {
        // Inside minGapMillis: this is what a wall reflection looks like.
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .clap()
            .clap()
            .silence(1_000L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `speech produces nothing`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .speech(millis = 600L)
            .silence(200L)
            .speech(millis = 500L)
            .silence(400L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `music produces nothing`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .music(millis = 4_000L)
            .silence(400L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `two doors closing produce nothing`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .thud()
            .silence(300L)
            .thud()
            .silence(500L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `two low pitched knocks on a table produce nothing`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .knock()
            .silence(300L)
            .knock()
            .silence(500L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    /**
     * Documents a real limitation rather than pretending it away.
     *
     * A hard, dry strike on a table radiates a broadband impulse that decays as fast
     * as a clap. On peak, attack, crest, spectral tilt and duration it *is* a clap,
     * so a deterministic feature detector accepts it. Separating the two needs
     * timbre modelling — the point at which the classifier this design leaves room
     * for would earn its place.
     */
    @Test
    fun `a sharp broadband strike is indistinguishable from a clap`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .knock(noiseFraction = 0.9f, decayMillis = 6f)
            .silence(300L)
            .knock(noiseFraction = 0.9f, decayMillis = 6f)
            .silence(500L)
            .toAudioInput()

        assertEquals(
            "known limitation: a dry broadband strike passes every feature gate",
            1,
            detector(input).events(configuration).toList().size,
        )
    }

    @Test
    fun `three unrelated bangs spread out produce nothing`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .clap()
            .silence(1_500L)
            .clap()
            .silence(1_500L)
            .clap()
            .silence(500L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `a triple clap yields a single event`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .clap()
            .silence(300L - CLAP_LENGTH_MILLIS)
            .clap()
            .silence(300L - CLAP_LENGTH_MILLIS)
            .clap()
            .silence(600L)
            .toAudioInput()

        assertEquals(
            "three claps are one gesture plus a clap swallowed by the cooldown",
            1,
            detector(input).events(configuration).toList().size,
        )
    }

    @Test
    fun `a burst of four claps yields a single event`() = runTest {
        val input = signal()
            .silence(WARM_UP_MILLIS)
            .clap()
            .silence(220L)
            .clap()
            .silence(220L)
            .clap()
            .silence(220L)
            .clap()
            .silence(600L)
            .toAudioInput()

        assertEquals(1, detector(input).events(configuration).toList().size)
    }

    @Test
    fun `claps during the warm up window are ignored`() = runTest {
        val input = signal()
            .clap()
            .silence(200L)
            .clap()
            .silence(600L)
            .toAudioInput()

        assertTrue(detector(input).events(configuration).toList().isEmpty())
    }

    @Test
    fun `a quiet clap in a noisy room is rejected but a loud one is heard`() = runTest {
        // Same gesture, same room noise, different clap level.
        fun scenario(amplitude: Float) = SyntheticSignal(audio, backgroundAmplitude = 0.01f)
            .silence(WARM_UP_MILLIS)
            .clap(amplitude = amplitude)
            .silence(300L - CLAP_LENGTH_MILLIS)
            .clap(amplitude = amplitude)
            .silence(500L)
            .toAudioInput()

        assertTrue(
            "a clap barely above the room should not register",
            detector(scenario(0.04f)).events(configuration).toList().isEmpty(),
        )
        assertEquals(
            "a clear clap should register in the same room",
            1,
            detector(scenario(0.6f)).events(configuration).toList().size,
        )
    }

    @Test
    fun `raising sensitivity lets a fainter clap through`() = runTest {
        // Peak lands between the High and Low peak gates, so sensitivity is the only
        // thing deciding the outcome.
        fun scenario() = signal()
            .silence(WARM_UP_MILLIS)
            .clap(amplitude = 0.10f)
            .silence(300L - CLAP_LENGTH_MILLIS)
            .clap(amplitude = 0.10f)
            .silence(500L)
            .toAudioInput()

        val strict = configuration.copy(sensitivity = SensitivityLevel.LOW)
        val permissive = configuration.copy(sensitivity = SensitivityLevel.HIGH)

        assertTrue(
            "Low should demand a louder clap",
            detector(scenario()).events(strict).toList().isEmpty(),
        )
        assertEquals(
            "High should hear the same clap",
            1,
            detector(scenario()).events(permissive).toList().size,
        )
    }

    /**
     * The real `AudioRecordInput` applies `flowOn(Dispatchers.IO)` because
     * `AudioRecord.read` blocks. Emitting a `TriggerEvent` from inside a `collect` of
     * that flow would break Flow's context invariant if the detector were structured
     * wrongly, and it would fail only on a device. This reproduces the dispatcher
     * boundary on the JVM.
     */
    @Test
    fun `events survive the audio source running on another dispatcher`() = runTest {
        val threaded = object : AudioInput {
            override fun frames(config: AudioCaptureConfig): Flow<AudioFrame> =
                doubleClap().toAudioInput().frames(config).flowOn(Dispatchers.IO)
        }

        val events = detector(threaded).events(configuration).toList()

        assertEquals(1, events.size)
    }

    @Test
    fun `state reports active while capturing and idle once finished`() = runTest {
        val detector = detector(doubleClap().toAudioInput())
        assertEquals(TriggerState.Idle, detector.state.value)

        detector.events(configuration).toList()

        assertEquals(TriggerState.Idle, detector.state.value)
    }

    @Test
    fun `cancelling collection returns the detector to idle`() = runTest {
        val detector = detector(doubleClap().toAudioInput(holdOpenAtEnd = true))

        val job = backgroundScope.launch { detector.events(configuration).toList() }
        runCurrent()
        assertEquals(TriggerState.Active, detector.state.value)

        job.cancel()
        runCurrent()

        assertEquals(TriggerState.Idle, detector.state.value)
    }

    @Test
    fun `a missing permission is reported and no audio is opened`() = runTest {
        var opened = false
        val input = object : AudioInput {
            override fun frames(config: AudioCaptureConfig): Flow<AudioFrame> = flow {
                opened = true
                awaitCancellation()
            }
        }

        val detector = detector(input, microphone = SensorAvailability.PERMISSION_REQUIRED)
        val job = backgroundScope.launch { detector.events(configuration).toList() }
        runCurrent()

        val state = detector.state.value
        assertTrue(state is TriggerState.Unavailable)
        assertEquals(
            TriggerState.Reason.MISSING_PERMISSION,
            (state as TriggerState.Unavailable).reason,
        )
        assertTrue("the microphone must not be opened without permission", !opened)
        job.cancel()
    }

    @Test
    fun `a device without a microphone is reported`() = runTest {
        val detector = detector(
            doubleClap().toAudioInput(holdOpenAtEnd = true),
            microphone = SensorAvailability.UNSUPPORTED,
        )

        val job = backgroundScope.launch { detector.events(configuration).toList() }
        runCurrent()

        assertEquals(
            TriggerState.Reason.MISSING_SENSOR,
            (detector.state.value as TriggerState.Unavailable).reason,
        )
        job.cancel()
    }

    @Test
    fun `a failing microphone surfaces as a failed state rather than crashing`() = runTest {
        val broken = object : AudioInput {
            override fun frames(config: AudioCaptureConfig): Flow<AudioFrame> = flow {
                throw AudioInputUnavailableException("Microphone was taken by another app")
            }
        }

        val detector = detector(broken)
        val events: List<TriggerEvent> = detector.events(configuration).toList()

        assertTrue(events.isEmpty())
        val state = detector.state.value
        assertTrue(state is TriggerState.Failed)
        assertEquals(
            "Microphone was taken by another app",
            (state as TriggerState.Failed).message,
        )
    }

    @Test
    fun `diagnostics report levels and counts without exposing audio`() = runTest {
        val detector = detector(doubleClap().toAudioInput(holdOpenAtEnd = true))

        val job = backgroundScope.launch { detector.events(configuration).toList() }
        // Subscribe so the detector publishes level updates at all.
        val observer = backgroundScope.launch { detector.diagnostics.collect { } }
        runCurrent()

        val diagnostics = detector.diagnostics.value
        assertTrue("should have heard both claps", diagnostics.candidateCount >= 2)
        assertEquals(1, diagnostics.detectionCount)
        assertTrue("gap reported", diagnostics.lastGapMillis != null)
        assertTrue("level reported", diagnostics.level > 0f)

        observer.cancel()
        job.cancel()
    }

    private companion object {
        const val FIXED_CLOCK_MILLIS = 987_654L

        /** Comfortably past [ClapProfile.warmUpMillis]. */
        const val WARM_UP_MILLIS = 700L

        /** Matches the default duration of [SyntheticSignal.clap]. */
        const val CLAP_LENGTH_MILLIS = 80L
    }
}
