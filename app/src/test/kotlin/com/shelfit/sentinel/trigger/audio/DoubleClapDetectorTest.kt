package com.shelfit.sentinel.trigger.audio

import com.shelfit.sentinel.core.FakeSensorStatusProvider
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the detector's preflight and lifecycle behaviour. Detection itself is not
 * implemented yet, so there is nothing else to assert — these tests are the
 * scaffold the stage 2 detection tests slot into.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DoubleClapDetectorTest {

    private fun detector(availability: SensorAvailability) = DoubleClapDetector(
        FakeSensorStatusProvider(mapOf(SensorKind.MICROPHONE to availability)),
    )

    @Test
    fun `reports missing permission when the microphone is not granted`() = runTest {
        val detector = detector(SensorAvailability.PERMISSION_REQUIRED)

        backgroundScope.launch { detector.events(DoubleClapConfiguration()).collect { } }
        runCurrent()

        val state = detector.state.value
        assertTrue(state is TriggerState.Unavailable)
        assertEquals(
            TriggerState.Reason.MISSING_PERMISSION,
            (state as TriggerState.Unavailable).reason,
        )
    }

    @Test
    fun `reports a missing sensor on a device with no microphone`() = runTest {
        val detector = detector(SensorAvailability.UNSUPPORTED)

        backgroundScope.launch { detector.events(DoubleClapConfiguration()).collect { } }
        runCurrent()

        val state = detector.state.value
        assertEquals(
            TriggerState.Reason.MISSING_SENSOR,
            (state as TriggerState.Unavailable).reason,
        )
    }

    @Test
    fun `reports not implemented while detection is unbuilt`() = runTest {
        val detector = detector(SensorAvailability.AVAILABLE)

        backgroundScope.launch { detector.events(DoubleClapConfiguration()).collect { } }
        runCurrent()

        val state = detector.state.value
        assertEquals(
            TriggerState.Reason.NOT_IMPLEMENTED,
            (state as TriggerState.Unavailable).reason,
        )
    }

    @Test
    fun `emits no events`() = runTest {
        val detector = detector(SensorAvailability.AVAILABLE)
        val received = mutableListOf<TriggerEvent>()

        backgroundScope.launch {
            detector.events(DoubleClapConfiguration()).collect { received += it }
        }
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `returns to idle when collection is cancelled`() = runTest {
        val detector = detector(SensorAvailability.AVAILABLE)

        val job = backgroundScope.launch {
            detector.events(DoubleClapConfiguration()).collect { }
        }
        runCurrent()
        job.cancel()
        runCurrent()

        assertEquals(TriggerState.Idle, detector.state.value)
    }

    @Test
    fun `tolerates a configuration of the wrong type`() = runTest {
        val detector = detector(SensorAvailability.AVAILABLE)
        val foreign = object : com.shelfit.sentinel.core.trigger.TriggerConfiguration {
            override val enabled: Boolean = true
        }

        backgroundScope.launch { detector.events(foreign).collect { } }
        runCurrent()

        assertTrue(detector.state.value is TriggerState.Unavailable)
    }
}
