package com.shelfit.sentinel.trigger.audio

/** Where the double clap gesture currently stands. Drives the test screen. */
sealed interface DoubleClapPhase {
    /** Nothing pending. */
    data object Idle : DoubleClapPhase

    /** One clap accepted; a second within the window completes the gesture. */
    data class AwaitingSecondClap(
        val firstClapAtMillis: Long,
        val expiresAtMillis: Long,
    ) : DoubleClapPhase

    /** A gesture just completed; further claps are ignored until this passes. */
    data class CoolingDown(val untilMillis: Long) : DoubleClapPhase
}

/** Result of offering a clap to the state machine. */
sealed interface DoubleClapOutcome {
    /** Stored as the first clap of a potential pair. */
    data object FirstClapAccepted : DoubleClapOutcome

    data class DoubleClapDetected(
        val firstClapAtMillis: Long,
        val secondClapAtMillis: Long,
        val gapMillis: Long,
        val confidence: Float,
    ) : DoubleClapOutcome

    data class Ignored(val reason: Reason) : DoubleClapOutcome {
        enum class Reason {
            /** Arrived inside [DoubleClapTiming.minGapMillis] — a reflection. */
            TOO_SOON_AFTER_FIRST,

            /** Arrived during the post-detection cooldown. */
            IN_COOLDOWN,
        }
    }
}

/**
 * Turns a stream of single claps into double clap gestures.
 *
 * ```
 * first valid clap -> AwaitingSecondClap -> second valid clap in window -> detected
 * ```
 *
 * All timing comes from [ClapCandidate.onsetMillis] on the capture timeline, and
 * time only moves forward when it is told to, by [onClapCandidate] or [advanceTo].
 * There is no clock inside, which is what lets the tests drive it with synthetic
 * claps at exact instants and get identical results every run.
 *
 * The interesting cases are the ones that must *not* fire:
 *  - A second clap sooner than [DoubleClapTiming.minGapMillis] is a room reflection
 *    of the first. It is ignored without disturbing the pending first clap, so the
 *    real second clap still completes the gesture.
 *  - A clap later than [DoubleClapTiming.maxGapMillis] does not complete the pair;
 *    it becomes the first clap of a new one. Three sounds spread across seconds
 *    therefore produce nothing.
 *  - After a detection nothing is accepted for [DoubleClapTiming.cooldownMillis],
 *    so a burst of claps yields one gesture rather than a stream of them.
 */
class DoubleClapStateMachine(private val timing: DoubleClapTiming) {

    var phase: DoubleClapPhase = DoubleClapPhase.Idle
        private set

    private var firstClap: ClapCandidate? = null

    fun reset() {
        phase = DoubleClapPhase.Idle
        firstClap = null
    }

    /**
     * Expires anything whose deadline has passed. Called on every audio frame so
     * the phase shown in the UI is current even while nothing is clapping.
     */
    fun advanceTo(nowMillis: Long) {
        when (val current = phase) {
            is DoubleClapPhase.AwaitingSecondClap ->
                if (nowMillis > current.expiresAtMillis) {
                    phase = DoubleClapPhase.Idle
                    firstClap = null
                }

            is DoubleClapPhase.CoolingDown ->
                if (nowMillis >= current.untilMillis) {
                    phase = DoubleClapPhase.Idle
                }

            DoubleClapPhase.Idle -> Unit
        }
    }

    fun onClapCandidate(candidate: ClapCandidate): DoubleClapOutcome {
        advanceTo(candidate.onsetMillis)

        return when (val current = phase) {
            is DoubleClapPhase.CoolingDown ->
                DoubleClapOutcome.Ignored(DoubleClapOutcome.Ignored.Reason.IN_COOLDOWN)

            DoubleClapPhase.Idle -> {
                firstClap = candidate
                phase = DoubleClapPhase.AwaitingSecondClap(
                    firstClapAtMillis = candidate.onsetMillis,
                    expiresAtMillis = candidate.onsetMillis + timing.maxGapMillis,
                )
                DoubleClapOutcome.FirstClapAccepted
            }

            is DoubleClapPhase.AwaitingSecondClap -> {
                val first = firstClap
                val gap = candidate.onsetMillis - current.firstClapAtMillis

                if (first == null || gap < timing.minGapMillis) {
                    // Too close to be a separate gesture. Keep waiting for the real
                    // second clap rather than consuming the pending one.
                    return DoubleClapOutcome.Ignored(
                        DoubleClapOutcome.Ignored.Reason.TOO_SOON_AFTER_FIRST,
                    )
                }

                phase = DoubleClapPhase.CoolingDown(
                    untilMillis = candidate.onsetMillis + timing.cooldownMillis,
                )
                firstClap = null

                DoubleClapOutcome.DoubleClapDetected(
                    firstClapAtMillis = first.onsetMillis,
                    secondClapAtMillis = candidate.onsetMillis,
                    gapMillis = gap,
                    confidence = (first.confidence + candidate.confidence) / 2f,
                )
            }
        }
    }
}
