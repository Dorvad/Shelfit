package com.shelfit.sentinel.core.diagnostics

import com.shelfit.sentinel.core.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogTest {

    private var now = 1_000L
    private val clock = WallClock { now }

    @Test
    fun `entries are newest first`() {
        val log = EventLog(clock)

        log.record(DiagnosticEvent.Kind.LISTENING_STARTED)
        now = 2_000L
        log.record(DiagnosticEvent.Kind.CLAP_CANDIDATE, "confidence 0.9")
        now = 3_000L
        log.record(DiagnosticEvent.Kind.DOUBLE_CLAP_DETECTED, "300 ms apart")

        val kinds = log.events.value.map { it.kind }
        assertEquals(
            listOf(
                DiagnosticEvent.Kind.DOUBLE_CLAP_DETECTED,
                DiagnosticEvent.Kind.CLAP_CANDIDATE,
                DiagnosticEvent.Kind.LISTENING_STARTED,
            ),
            kinds,
        )
    }

    @Test
    fun `each entry is stamped with the wall clock`() {
        val log = EventLog(clock)
        now = 1_700_000_000_000L

        log.record(DiagnosticEvent.Kind.COOLDOWN_ENDED)

        assertEquals(1_700_000_000_000L, log.events.value.single().atEpochMillis)
    }

    @Test
    fun `the log is bounded so it cannot grow without limit`() {
        val log = EventLog(clock, capacity = 5)

        repeat(50) { index ->
            log.record(DiagnosticEvent.Kind.CLAP_CANDIDATE, "confidence 0.$index")
        }

        assertEquals(5, log.events.value.size)
        assertEquals(
            "the newest entry must survive",
            "confidence 0.49",
            log.events.value.first().detail,
        )
    }

    @Test
    fun `clear empties the log`() {
        val log = EventLog(clock)
        log.record(DiagnosticEvent.Kind.LISTENING_STARTED)

        log.clear()

        assertTrue(log.events.value.isEmpty())
    }

    @Test
    fun `a detail is optional`() {
        val log = EventLog(clock)

        log.record(DiagnosticEvent.Kind.AWAITING_SECOND_CLAP)

        assertEquals(null, log.events.value.single().detail)
    }

    /**
     * Structural guarantee rather than a behavioural one: a log entry holds a
     * timestamp, an enum and a short string. Adding a field capable of carrying
     * samples — an array, a buffer, a collection — fails this test, which is the point.
     */
    @Test
    fun `an entry can only hold metadata`() {
        val log = EventLog(clock)
        log.record(DiagnosticEvent.Kind.CLAP_CANDIDATE, "confidence 0.91")

        assertTrue(log.events.value.single().detail!!.length < MAX_DETAIL_LENGTH)

        val offending = DiagnosticEvent::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filter { field ->
                field.type.isArray ||
                    Collection::class.java.isAssignableFrom(field.type) ||
                    java.nio.Buffer::class.java.isAssignableFrom(field.type)
            }
            .map { it.name }

        assertEquals("a log entry must not be able to hold audio", emptyList<String>(), offending)
    }

    private companion object {
        const val MAX_DETAIL_LENGTH = 64
    }
}
