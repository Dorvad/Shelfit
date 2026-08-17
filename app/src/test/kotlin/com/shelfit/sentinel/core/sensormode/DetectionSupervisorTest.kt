package com.shelfit.sentinel.core.sensormode

import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unattended recovery, driven with virtual time.
 *
 * This is the behaviour that decides whether a phone left on a shelf is still listening
 * a week later, and none of it can be observed by hand — hence testing it against a fake
 * state flow rather than a real microphone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectionSupervisorTest {

    private val triggerId = TriggerId("audio.double_clap")
    private val states = MutableStateFlow<Map<TriggerId, TriggerState>>(
        mapOf(triggerId to TriggerState.Idle),
    )

    private val starts = mutableListOf<Long>()
    private val stops = mutableListOf<Long>()
    private val reports = mutableListOf<SupervisorReport>()

    private fun supervisor(policy: RecoveryPolicy = RecoveryPolicy()) = DetectionSupervisor(
        states = states,
        start = { starts += starts.size.toLong() },
        stop = { stops += stops.size.toLong() },
        report = { reports += it },
        policy = policy,
    )

    private fun emit(state: TriggerState) {
        states.value = mapOf(triggerId to state)
    }

    @Test
    fun `starts capture as soon as it runs`() = runTest {
        backgroundScope.launch { supervisor().run() }
        runCurrent()

        assertEquals(1, starts.size)
        assertTrue(reports.contains(SupervisorReport.Started))
    }

    @Test
    fun `restarts capture after a failure, once the backoff has elapsed`() = runTest {
        val policy = RecoveryPolicy(initialDelayMillis = 5_000L)
        backgroundScope.launch { supervisor(policy).run() }
        runCurrent()

        emit(TriggerState.Failed("Microphone was taken by another app"))
        runCurrent()

        assertEquals("capture is released while waiting", 1, stops.size)
        assertEquals("but not restarted yet", 1, starts.size)

        advanceTimeBy(5_001L)
        runCurrent()

        assertEquals("restarted after the backoff", 2, starts.size)
    }

    /**
     * The realistic interaction, and the one a naive implementation gets wrong: releasing
     * capture pushes a new state through the flow while the backoff is still waiting. If
     * that supersedes the wait, the retry never happens and Sensor Mode dies silently.
     */
    @Test
    fun `a state change caused by stopping does not cancel the pending retry`() = runTest {
        val policy = RecoveryPolicy(initialDelayMillis = 5_000L)
        val supervisor = DetectionSupervisor(
            states = states,
            start = { starts += starts.size.toLong() },
            stop = {
                stops += stops.size.toLong()
                // Exactly what the engine does when detection is stopped.
                emit(TriggerState.Idle)
            },
            report = { reports += it },
            policy = policy,
        )
        backgroundScope.launch { supervisor.run() }
        runCurrent()

        emit(TriggerState.Failed("microphone taken"))
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()

        assertEquals("the retry must still fire", 2, starts.size)
    }

    @Test
    fun `backs off further on each consecutive failure`() = runTest {
        val policy = RecoveryPolicy(initialDelayMillis = 1_000L, multiplier = 2f)
        backgroundScope.launch { supervisor(policy).run() }
        runCurrent()

        // First failure: 1s.
        emit(TriggerState.Failed("one"))
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(2, starts.size)

        // Second failure: 2s, so one second is not enough.
        emit(TriggerState.Failed("two"))
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals("still waiting out the longer backoff", 2, starts.size)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(3, starts.size)

        val delays = reports.filterIsInstance<SupervisorReport.Recovering>().map { it.delayMillis }
        assertEquals(listOf(1_000L, 2_000L), delays)
    }

    @Test
    fun `resets the backoff once capture comes back`() = runTest {
        val policy = RecoveryPolicy(initialDelayMillis = 1_000L, multiplier = 4f)
        backgroundScope.launch { supervisor(policy).run() }
        runCurrent()

        emit(TriggerState.Failed("one"))
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()

        emit(TriggerState.Active)
        runCurrent()

        emit(TriggerState.Failed("two"))
        runCurrent()

        val delays = reports.filterIsInstance<SupervisorReport.Recovering>().map { it.delayMillis }
        assertEquals(
            "a recovered session must not inherit the previous backoff",
            listOf(1_000L, 1_000L),
            delays,
        )
        assertTrue(reports.any { it is SupervisorReport.Recovered })
    }

    @Test
    fun `reports recovery only after an actual failure`() = runTest {
        backgroundScope.launch { supervisor().run() }
        runCurrent()

        emit(TriggerState.Active)
        runCurrent()

        assertTrue(
            "a clean start is not a recovery",
            reports.none { it is SupervisorReport.Recovered },
        )
    }

    @Test
    fun `does not retry when the microphone permission has gone`() = runTest {
        backgroundScope.launch { supervisor().run() }
        runCurrent()

        emit(
            TriggerState.Unavailable(
                TriggerState.Reason.MISSING_PERMISSION,
                "android.permission.RECORD_AUDIO",
            ),
        )
        runCurrent()
        advanceTimeBy(600_000L)
        runCurrent()

        assertEquals("capture released", 1, stops.size)
        assertEquals("and never restarted — retrying cannot grant a permission", 1, starts.size)
        assertTrue(reports.contains(SupervisorReport.PermissionLost))
    }

    /**
     * Supervision must end when the permission goes, not merely stop retrying. A job left
     * alive would make the service's "already supervising" guard refuse to start a fresh
     * supervisor when the user grants the permission and taps Resume.
     */
    @Test
    fun `supervision finishes after permission loss so it can be restarted`() = runTest {
        val job = backgroundScope.launch { supervisor().run() }
        runCurrent()

        emit(
            TriggerState.Unavailable(
                TriggerState.Reason.MISSING_PERMISSION,
                "android.permission.RECORD_AUDIO",
            ),
        )
        runCurrent()

        assertTrue("the supervisor job must complete", job.isCompleted)
        assertTrue("and not by being cancelled", !job.isCancelled)
    }

    @Test
    fun `keeps recovering across a long outage without giving up`() = runTest {
        val policy = RecoveryPolicy(initialDelayMillis = 1_000L, maxDelayMillis = 4_000L)
        backgroundScope.launch { supervisor(policy).run() }
        runCurrent()

        repeat(6) { attempt ->
            emit(TriggerState.Failed("attempt $attempt"))
            runCurrent()
            advanceTimeBy(policy.delayFor(attempt + 1) + 1L)
            runCurrent()
        }

        assertEquals("one initial start plus six retries", 7, starts.size)
    }

    @Test
    fun `an unavailable sensor is not treated as a recoverable failure`() = runTest {
        backgroundScope.launch { supervisor().run() }
        runCurrent()

        emit(TriggerState.Unavailable(TriggerState.Reason.MISSING_SENSOR, "MICROPHONE"))
        runCurrent()
        advanceTimeBy(600_000L)
        runCurrent()

        assertEquals("no device, nothing to retry", 1, starts.size)
        assertTrue(reports.none { it is SupervisorReport.Recovering })
    }
}
