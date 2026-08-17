package com.shelfit.sentinel.trigger.audio

import android.Manifest
import com.shelfit.sentinel.core.MonotonicClock
import com.shelfit.sentinel.core.audio.AudioInput
import com.shelfit.sentinel.core.audio.AudioInputUnavailableException
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import com.shelfit.sentinel.core.diagnostics.EventLog
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.sensor.SensorStatusProvider
import com.shelfit.sentinel.core.trigger.Trigger
import com.shelfit.sentinel.core.trigger.TriggerConfiguration
import com.shelfit.sentinel.core.trigger.TriggerDetector
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Hears two claps and reports one [TriggerEvent].
 *
 * Owns no signal processing itself — it wires together four pieces that each do one
 * job, so the analysis can be tested without a microphone and the microphone can be
 * swapped without touching the analysis:
 *
 * ```
 * AudioInput -> ClapFeatureExtractor -> ClapCandidateDetector -> DoubleClapStateMachine
 *   PCM             measurements            "that was a clap"        "that was two"
 * ```
 *
 * **Privacy.** Frames are reduced to a handful of numbers and dropped; nothing is
 * written to disk, cached, or sent anywhere. [ClapDiagnostics] publishes levels and
 * counts, never samples, and [TriggerEvent.detail] carries only the gap between the
 * two claps.
 *
 * **Timing.** Analysis runs on the capture timeline — sample counts, not a clock —
 * so detection is unaffected by scheduling jitter. Only the emitted event is stamped
 * with [MonotonicClock], because the rule layer compares that against other
 * triggers.
 *
 * **Lifecycle.** All per-session state lives inside the [events] flow, so two
 * collections never share a state machine, and cancelling the flow tears down the
 * pipeline and releases the microphone.
 */
class DoubleClapDetector(
    private val audioInput: AudioInput,
    private val sensorStatus: SensorStatusProvider,
    private val clock: MonotonicClock,
    private val eventLog: EventLog? = null,
) : TriggerDetector {

    override val trigger: Trigger = DoubleClapTrigger

    private val _state = MutableStateFlow<TriggerState>(TriggerState.Idle)
    override val state: StateFlow<TriggerState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(ClapDiagnostics())

    /** Audio-only live view, consumed by the detector test screen. */
    val diagnostics: StateFlow<ClapDiagnostics> = _diagnostics.asStateFlow()

    override fun events(configuration: TriggerConfiguration): Flow<TriggerEvent> =
        flow {
            _state.value = TriggerState.Starting

            val config = configuration as? DoubleClapConfiguration
                ?: DoubleClapTrigger.defaultConfiguration as DoubleClapConfiguration

            if (!preflight()) awaitCancellation()

            val profile = config.effectiveProfile
            val extractor = ClapFeatureExtractor(config.audio, profile)
            val candidates = ClapCandidateDetector(config.audio, profile)
            val gesture = DoubleClapStateMachine(config.timing)
            val session = SessionTotals()

            _diagnostics.value = ClapDiagnostics(listening = true)
            _state.value = TriggerState.Active
            eventLog?.record(DiagnosticEvent.Kind.LISTENING_STARTED)

            audioInput.frames(config.audio).collect { frame ->
                val features = extractor.extract(frame)

                val phaseBefore = gesture.phase
                gesture.advanceTo(features.timestampMillis)
                logPhaseTransition(phaseBefore, gesture.phase)

                var event: TriggerEvent? = null
                var significant = false

                when (val detection = candidates.onFrame(features)) {
                    ClapDetection.None -> Unit

                    is ClapDetection.Rejected -> {
                        session.lastRejection = detection.reason
                        significant = true
                        logRejection(detection.reason, session)
                    }

                    is ClapDetection.Candidate -> {
                        session.candidateCount++
                        session.lastCandidateAtMillis = detection.clap.onsetMillis
                        session.lastCandidateConfidence = detection.clap.confidence
                        session.lastRejection = null
                        session.lastLoggedRejection = null
                        significant = true
                        eventLog?.record(
                            DiagnosticEvent.Kind.CLAP_CANDIDATE,
                            formatConfidence(detection.clap.confidence),
                        )

                        val outcome = gesture.onClapCandidate(detection.clap)
                        if (outcome is DoubleClapOutcome.FirstClapAccepted) {
                            eventLog?.record(DiagnosticEvent.Kind.AWAITING_SECOND_CLAP)
                        }
                        if (outcome is DoubleClapOutcome.DoubleClapDetected) {
                            session.detectionCount++
                            session.lastGapMillis = outcome.gapMillis
                            session.lastDetectionConfidence = outcome.confidence
                            eventLog?.record(
                                DiagnosticEvent.Kind.DOUBLE_CLAP_DETECTED,
                                "${outcome.gapMillis} ms apart, " +
                                    formatConfidence(outcome.confidence),
                            )
                            event = TriggerEvent(
                                triggerId = trigger.id,
                                elapsedRealtimeMillis = clock.elapsedMillis(),
                                confidence = outcome.confidence,
                                detail = mapOf(GAP_DETAIL_KEY to outcome.gapMillis.toString()),
                            )
                        }
                    }
                }

                publishDiagnostics(features, gesture.phase, candidates, session, significant)

                event?.let { emit(it) }
            }
        }
            .catch { error ->
                val message = describe(error)
                _state.value = TriggerState.Failed(message)
                eventLog?.record(DiagnosticEvent.Kind.DETECTOR_FAILED, message)
            }
            .onCompletion {
                val wasListening = _diagnostics.value.listening
                _diagnostics.value = ClapDiagnostics()
                if (wasListening) eventLog?.record(DiagnosticEvent.Kind.LISTENING_STOPPED)
                // A failure is worth leaving on screen; anything else is just a stop.
                if (_state.value !is TriggerState.Failed) {
                    _state.value = TriggerState.Idle
                }
            }

    /** Returns true when capture may proceed; otherwise sets the reason on [state]. */
    private fun preflight(): Boolean {
        val microphone = sensorStatus.statusOf(SensorKind.MICROPHONE)
        return when (microphone.availability) {
            SensorAvailability.UNSUPPORTED -> {
                _state.value = TriggerState.missingSensor(SensorKind.MICROPHONE)
                false
            }

            SensorAvailability.PERMISSION_REQUIRED -> {
                _state.value = TriggerState.missingPermission(Manifest.permission.RECORD_AUDIO)
                false
            }

            SensorAvailability.AVAILABLE -> true
        }
    }

    /**
     * Publishes to [diagnostics].
     *
     * Level updates are skipped entirely when nothing is observing, and throttled
     * when something is: 60 frames a second of recomposition would cost more battery
     * than the detection itself. Claps and rejections always publish — they are rare
     * and they are the point.
     */
    /**
     * Logs the transitions the state machine makes on its own, as timers expire.
     * These have no triggering frame, so they would otherwise be invisible.
     */
    private fun logPhaseTransition(before: DoubleClapPhase, after: DoubleClapPhase) {
        if (before === after) return
        when {
            before is DoubleClapPhase.AwaitingSecondClap && after is DoubleClapPhase.Idle ->
                eventLog?.record(DiagnosticEvent.Kind.SECOND_CLAP_TIMED_OUT)

            before is DoubleClapPhase.CoolingDown && after is DoubleClapPhase.Idle ->
                eventLog?.record(DiagnosticEvent.Kind.COOLDOWN_ENDED)

            else -> Unit
        }
    }

    /**
     * Logs a rejection, collapsing repeats.
     *
     * A burst of noise produces the same reason many times over; recording each one
     * would push everything useful out of a bounded log.
     */
    private fun logRejection(reason: ClapRejection, session: SessionTotals) {
        if (session.lastLoggedRejection == reason) return
        session.lastLoggedRejection = reason
        val kind = if (reason == ClapRejection.TOO_MANY_TRANSIENTS) {
            DiagnosticEvent.Kind.TRANSIENTS_SUPPRESSED
        } else {
            DiagnosticEvent.Kind.CLAP_REJECTED
        }
        eventLog?.record(kind, reason.name)
    }

    private fun formatConfidence(confidence: Float): String =
        "confidence ${(confidence * CONFIDENCE_PERCENT).toInt() / CONFIDENCE_PERCENT}"

    private fun publishDiagnostics(
        features: AudioFrameFeatures,
        phase: DoubleClapPhase,
        candidates: ClapCandidateDetector,
        session: SessionTotals,
        significant: Boolean,
    ) {
        val observed = _diagnostics.subscriptionCount.value > 0
        if (!observed && !significant) return

        val dueForLevelUpdate =
            features.timestampMillis - session.lastPublishMillis >= LEVEL_PUBLISH_INTERVAL_MILLIS
        if (!significant && !dueForLevelUpdate) return

        session.lastPublishMillis = features.timestampMillis

        val gate = candidates.effectiveMinPeak
        if (gate > session.lastReportedGate * GATE_CHANGE_FACTOR) {
            eventLog?.record(
                DiagnosticEvent.Kind.AMBIENT_THRESHOLD_RAISED,
                "peak gate now ${"%.3f".format(gate)}",
            )
        }
        session.lastReportedGate = gate

        _diagnostics.value = ClapDiagnostics(
            listening = true,
            level = features.rms,
            peak = features.peak,
            noiseFloor = features.noiseFloor,
            ambientPeak = features.ambientPeak,
            effectiveMinPeak = gate,
            suppressingBurst = candidates.suppressingBurst,
            phase = phase,
            candidateCount = session.candidateCount,
            detectionCount = session.detectionCount,
            lastCandidateAtMillis = session.lastCandidateAtMillis,
            lastCandidateConfidence = session.lastCandidateConfidence,
            lastRejection = session.lastRejection,
            lastGapMillis = session.lastGapMillis,
            lastDetectionConfidence = session.lastDetectionConfidence,
            timelineMillis = features.timestampMillis,
        )
    }

    private fun describe(error: Throwable): String = when (error) {
        is AudioInputUnavailableException -> error.message ?: "Microphone unavailable"
        else -> error.message ?: error::class.simpleName ?: "Audio capture failed"
    }

    /** Per-collection counters. Never shared between sessions. */
    private class SessionTotals {
        var candidateCount = 0
        var detectionCount = 0
        var lastCandidateAtMillis: Long? = null
        var lastCandidateConfidence: Float? = null
        var lastRejection: ClapRejection? = null
        var lastGapMillis: Long? = null
        var lastDetectionConfidence: Float? = null
        var lastPublishMillis = Long.MIN_VALUE
        var lastLoggedRejection: ClapRejection? = null

        /** Last gate value logged, so only meaningful movement is recorded. */
        var lastReportedGate = 0f
    }

    private companion object {
        const val LEVEL_PUBLISH_INTERVAL_MILLIS = 64L
        const val GAP_DETAIL_KEY = "gapMillis"
        const val CONFIDENCE_PERCENT = 100f

        /** Only log the adaptive gate when it moves by half as much again. */
        const val GATE_CHANGE_FACTOR = 1.5f
    }
}
