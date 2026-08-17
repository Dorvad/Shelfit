package com.shelfit.sentinel.trigger.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture timing, driven by synthetic claps at exact instants. No audio and no
 * clock involved, so every case here is deterministic.
 */
class DoubleClapStateMachineTest {

    private val timing = DoubleClapTiming(
        minGapMillis = 120L,
        maxGapMillis = 900L,
        cooldownMillis = 1_500L,
    )

    private fun machine() = DoubleClapStateMachine(timing)

    private fun clap(atMillis: Long, confidence: Float = 0.8f) = ClapCandidate(
        onsetMillis = atMillis,
        confidence = confidence,
        peak = 0.4f,
        ambientRatio = 50f,
        transientMillis = 40L,
    )

    @Test
    fun `two claps inside the window are one gesture`() {
        val machine = machine()

        assertEquals(DoubleClapOutcome.FirstClapAccepted, machine.onClapCandidate(clap(1_000L)))

        val outcome = machine.onClapCandidate(clap(1_300L))

        assertTrue(outcome is DoubleClapOutcome.DoubleClapDetected)
        outcome as DoubleClapOutcome.DoubleClapDetected
        assertEquals(1_000L, outcome.firstClapAtMillis)
        assertEquals(1_300L, outcome.secondClapAtMillis)
        assertEquals(300L, outcome.gapMillis)
    }

    @Test
    fun `first clap alone never completes a gesture`() {
        val machine = machine()

        machine.onClapCandidate(clap(1_000L))
        machine.advanceTo(1_000L + timing.maxGapMillis + 1L)

        assertEquals(DoubleClapPhase.Idle, machine.phase)
    }

    @Test
    fun `while waiting the phase reports the pending first clap`() {
        val machine = machine()

        machine.onClapCandidate(clap(1_000L))

        val phase = machine.phase
        assertTrue(phase is DoubleClapPhase.AwaitingSecondClap)
        phase as DoubleClapPhase.AwaitingSecondClap
        assertEquals(1_000L, phase.firstClapAtMillis)
        assertEquals(1_000L + timing.maxGapMillis, phase.expiresAtMillis)
    }

    @Test
    fun `an echo of the first clap is ignored without consuming the window`() {
        val machine = machine()
        machine.onClapCandidate(clap(1_000L))

        // Well inside minGap: a reflection off a wall, not a second gesture.
        val echo = machine.onClapCandidate(clap(1_040L))

        assertEquals(
            DoubleClapOutcome.Ignored(DoubleClapOutcome.Ignored.Reason.TOO_SOON_AFTER_FIRST),
            echo,
        )
        assertTrue(machine.phase is DoubleClapPhase.AwaitingSecondClap)

        // The real second clap still completes the gesture.
        val outcome = machine.onClapCandidate(clap(1_320L))
        assertTrue(outcome is DoubleClapOutcome.DoubleClapDetected)
        assertEquals(320L, (outcome as DoubleClapOutcome.DoubleClapDetected).gapMillis)
    }

    @Test
    fun `a clap exactly at the minimum gap counts`() {
        val machine = machine()
        machine.onClapCandidate(clap(1_000L))

        val outcome = machine.onClapCandidate(clap(1_000L + timing.minGapMillis))

        assertTrue(outcome is DoubleClapOutcome.DoubleClapDetected)
    }

    @Test
    fun `a clap after the window becomes the start of a new pair`() {
        val machine = machine()
        machine.onClapCandidate(clap(1_000L))

        val late = machine.onClapCandidate(clap(1_000L + timing.maxGapMillis + 100L))

        assertEquals(DoubleClapOutcome.FirstClapAccepted, late)
    }

    @Test
    fun `three unrelated bangs spread over seconds produce nothing`() {
        val machine = machine()
        val outcomes = listOf(0L, 2_000L, 4_000L).map { machine.onClapCandidate(clap(it)) }

        assertTrue(outcomes.none { it is DoubleClapOutcome.DoubleClapDetected })
    }

    @Test
    fun `a burst of claps yields exactly one gesture`() {
        val machine = machine()
        val outcomes = listOf(0L, 250L, 500L, 750L, 1_000L)
            .map { machine.onClapCandidate(clap(it)) }

        assertEquals(1, outcomes.count { it is DoubleClapOutcome.DoubleClapDetected })
        assertTrue(
            "claps after the gesture are swallowed by the cooldown",
            outcomes.drop(2).all {
                it == DoubleClapOutcome.Ignored(DoubleClapOutcome.Ignored.Reason.IN_COOLDOWN)
            },
        )
    }

    @Test
    fun `a second gesture is possible once the cooldown expires`() {
        val machine = machine()
        machine.onClapCandidate(clap(0L))
        machine.onClapCandidate(clap(300L))

        val afterCooldown = 300L + timing.cooldownMillis
        assertEquals(
            DoubleClapOutcome.FirstClapAccepted,
            machine.onClapCandidate(clap(afterCooldown)),
        )
        assertTrue(
            machine.onClapCandidate(clap(afterCooldown + 300L))
                is DoubleClapOutcome.DoubleClapDetected,
        )
    }

    @Test
    fun `the cooldown is still in force one millisecond early`() {
        val machine = machine()
        machine.onClapCandidate(clap(0L))
        machine.onClapCandidate(clap(300L))

        val outcome = machine.onClapCandidate(clap(300L + timing.cooldownMillis - 1L))

        assertEquals(
            DoubleClapOutcome.Ignored(DoubleClapOutcome.Ignored.Reason.IN_COOLDOWN),
            outcome,
        )
    }

    @Test
    fun `gesture confidence averages the two claps`() {
        val machine = machine()
        machine.onClapCandidate(clap(0L, confidence = 0.6f))

        val outcome = machine.onClapCandidate(clap(300L, confidence = 1.0f))

        assertEquals(
            0.8f,
            (outcome as DoubleClapOutcome.DoubleClapDetected).confidence,
            1e-5f,
        )
    }

    @Test
    fun `reset clears a pending first clap`() {
        val machine = machine()
        machine.onClapCandidate(clap(1_000L))

        machine.reset()

        assertEquals(DoubleClapPhase.Idle, machine.phase)
        assertEquals(
            DoubleClapOutcome.FirstClapAccepted,
            machine.onClapCandidate(clap(1_100L)),
        )
    }
}
