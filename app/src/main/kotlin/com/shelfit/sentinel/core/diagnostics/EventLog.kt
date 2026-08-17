package com.shelfit.sentinel.core.diagnostics

import com.shelfit.sentinel.core.WallClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One line of history.
 *
 * **Metadata only.** A confidence number, a millisecond count, a state name. There
 * is no field here that could hold a sample, and nothing in the app writes audio
 * anywhere, so a log line can never carry sensor content.
 *
 * @param atEpochMillis when it happened, for display.
 * @param detail short qualifier — a confidence, a gap, a reason. Never sensor data.
 */
data class DiagnosticEvent(
    val atEpochMillis: Long,
    val kind: Kind,
    val detail: String? = null,
) {
    enum class Kind {
        LISTENING_STARTED,
        LISTENING_STOPPED,
        DETECTOR_FAILED,
        CLAP_CANDIDATE,
        CLAP_REJECTED,
        AWAITING_SECOND_CLAP,
        DOUBLE_CLAP_DETECTED,
        SECOND_CLAP_TIMED_OUT,
        COOLDOWN_ENDED,
        TRANSIENTS_SUPPRESSED,
        AMBIENT_THRESHOLD_RAISED,
        CALIBRATION_SAVED,
        CALIBRATION_CLEARED,
    }
}

/**
 * Bounded, in-memory history of what the detector decided and why.
 *
 * Deliberately not persisted. It exists to answer "why did that clap not register?"
 * while the phone is in front of you, and keeping it in memory means there is no
 * file to reason about, no retention policy to get wrong, and nothing left behind
 * when the process dies.
 *
 * Oldest entries are dropped once [capacity] is reached. Appending is cheap and
 * emissions are rare in normal use — a quiet room produces no entries at all.
 */
class EventLog(
    private val clock: WallClock,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())

    /** Newest first, so the UI can render it without reversing. */
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun record(kind: DiagnosticEvent.Kind, detail: String? = null) {
        val event = DiagnosticEvent(clock.epochMillis(), kind, detail)
        _events.value = (listOf(event) + _events.value).take(capacity)
    }

    fun clear() {
        _events.value = emptyList()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
