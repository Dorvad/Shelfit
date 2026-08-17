package com.shelfit.sentinel.core.sensormode

import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.pow

/**
 * How long to wait before trying capture again.
 *
 * Exponential, because the common cause is another app holding the microphone — a
 * phone call, a voice assistant — and those last seconds to minutes. Retrying every
 * second for the duration of a call would waste power and achieve nothing; the cap
 * stops a permanently broken microphone from retrying forever at speed.
 */
class RecoveryPolicy(
    private val initialDelayMillis: Long = 5_000L,
    private val maxDelayMillis: Long = 300_000L,
    private val multiplier: Float = 2f,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis is the ceiling" }
        require(multiplier >= 1f) { "multiplier must not shrink the delay" }
    }

    /** @param attempt 1 for the first retry. */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt is one-based" }
        val scaled = initialDelayMillis * multiplier.toDouble().pow(attempt - 1)
        return scaled.coerceAtMost(maxDelayMillis.toDouble()).toLong()
    }
}

/** What the supervisor did, for the notification, the health record and the log. */
sealed interface SupervisorReport {
    data object Started : SupervisorReport

    /** Capture failed; another attempt is scheduled. */
    data class Recovering(
        val attempt: Int,
        val delayMillis: Long,
        val detail: String?,
    ) : SupervisorReport

    /** Capture came back on its own. */
    data class Recovered(val afterAttempts: Int) : SupervisorReport

    /** The microphone permission is gone. No amount of retrying will help. */
    data object PermissionLost : SupervisorReport
}

/**
 * Keeps detection running for weeks without anyone watching.
 *
 * A phone left plugged in will lose the microphone occasionally: a call arrives, an
 * assistant wakes up, the audio server restarts. None of those should end Sensor Mode,
 * and none of them should need a person. This restarts capture with a backoff and
 * reports what it did.
 *
 * It deliberately does *not* retry when the microphone permission has been revoked.
 * That cannot be fixed by trying again, and a retry loop against a permission denial
 * is how an always-on app ends up burning battery to accomplish nothing.
 *
 * Pure Kotlin — no Android, no clock, no dispatcher of its own — so the whole recovery
 * behaviour is testable with virtual time.
 */
class DetectionSupervisor(
    private val states: Flow<Map<TriggerId, TriggerState>>,
    private val start: suspend () -> Unit,
    private val stop: suspend () -> Unit,
    private val report: suspend (SupervisorReport) -> Unit,
    private val policy: RecoveryPolicy = RecoveryPolicy(),
) {

    /**
     * Runs until cancelled. Cancelling is the only way out, and it leaves capture
     * stopped by whoever owns the scope — this does not stop on the way out, because
     * the caller may be cancelling precisely in order to hand the microphone to
     * something else.
     */
    suspend fun run() {
        var attempt = 0

        start()
        report(SupervisorReport.Started)

        try {
            // Plain collect, deliberately. collectLatest would cancel a backoff wait the
            // moment stopping capture pushed a new state through, and the retry would
            // never fire. States arrive from a StateFlow, so anything that happens during
            // a wait is conflated and the newest value is what we see afterwards.
            states.collect { snapshot ->
                val failure = snapshot.values
                    .filterIsInstance<TriggerState.Failed>()
                    .firstOrNull()
                val permissionLost = snapshot.values
                    .filterIsInstance<TriggerState.Unavailable>()
                    .any { it.reason == TriggerState.Reason.MISSING_PERMISSION }

                when {
                    permissionLost -> {
                        stop()
                        report(SupervisorReport.PermissionLost)
                        // Finish, rather than keep watching. Supervision is over until
                        // the user acts, and leaving the job alive would make a later
                        // "already supervising" check refuse to start a fresh one.
                        throw Finished()
                    }

                    failure != null -> {
                        attempt++
                        val wait = policy.delayFor(attempt)
                        report(SupervisorReport.Recovering(attempt, wait, failure.message))
                        stop()
                        delay(wait)
                        start()
                    }

                    snapshot.values.any { it is TriggerState.Active } -> {
                        if (attempt > 0) {
                            report(SupervisorReport.Recovered(attempt))
                            attempt = 0
                        }
                    }
                }
            }
        } catch (finished: Finished) {
            // Deliberate end. Only this exact type is swallowed, so a real cancellation
            // still propagates.
            check(finished.message != null)
        }
    }

    /**
     * Ends [run] from inside the collector.
     *
     * A `CancellationException` subclass so it unwinds the collection cleanly; a distinct
     * type so it can be caught without swallowing genuine cancellation.
     */
    private class Finished : CancellationException("supervision finished")
}
