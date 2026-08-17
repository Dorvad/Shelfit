package com.shelfit.sentinel.core.trigger

import com.shelfit.sentinel.core.FakeConfiguration
import com.shelfit.sentinel.core.FakeTrigger
import com.shelfit.sentinel.core.FakeTriggerDetector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TriggerEngineTest {

    private val triggerA = FakeTrigger(TriggerId("test.a"))
    private val triggerB = FakeTrigger(TriggerId("test.b"))

    @Test
    fun `forwards events from started detectors`() = runTest {
        val event = TriggerEvent(triggerA.id, elapsedRealtimeMillis = 10L)
        val detector = FakeTriggerDetector(triggerA, listOf(event))
        val engine = TriggerEngine(TriggerRegistry(listOf(detector)), backgroundScope)

        val received = mutableListOf<TriggerEvent>()
        backgroundScope.launch { engine.events.collect { received += it } }
        runCurrent()

        engine.start()
        runCurrent()

        assertEquals(listOf(event), received)
        assertTrue(engine.isRunning.value)
    }

    @Test
    fun `does not start detectors whose configuration is disabled`() = runTest {
        val detector = FakeTriggerDetector(triggerA)
        val engine = TriggerEngine(TriggerRegistry(listOf(detector)), backgroundScope)

        engine.start(mapOf(triggerA.id to FakeConfiguration(enabled = false)))
        runCurrent()

        assertEquals(0, detector.collectCount)
    }

    @Test
    fun `passes the supplied configuration to the detector`() = runTest {
        val detector = FakeTriggerDetector(triggerA)
        val engine = TriggerEngine(TriggerRegistry(listOf(detector)), backgroundScope)
        val configuration = FakeConfiguration(enabled = true)

        engine.start(mapOf(triggerA.id to configuration))
        runCurrent()

        assertEquals(configuration, detector.lastConfiguration)
    }

    @Test
    fun `falls back to the trigger default when no configuration is supplied`() = runTest {
        val detector = FakeTriggerDetector(triggerA)
        val engine = TriggerEngine(TriggerRegistry(listOf(detector)), backgroundScope)

        engine.start()
        runCurrent()

        assertEquals(triggerA.defaultConfiguration, detector.lastConfiguration)
    }

    @Test
    fun `start is idempotent while running`() = runTest {
        val detector = FakeTriggerDetector(triggerA)
        val engine = TriggerEngine(TriggerRegistry(listOf(detector)), backgroundScope)

        engine.start()
        runCurrent()
        engine.start()
        runCurrent()

        assertEquals(1, detector.collectCount)
    }

    @Test
    fun `stop cancels collection so detectors release their sensors`() = runTest {
        val detector = FakeTriggerDetector(triggerA)
        val engine = TriggerEngine(TriggerRegistry(listOf(detector)), backgroundScope)

        engine.start()
        runCurrent()
        assertEquals(TriggerState.Active, detector.state.value)

        engine.stop()
        runCurrent()

        assertEquals(TriggerState.Idle, detector.state.value)
        assertFalse(engine.isRunning.value)
    }

    @Test
    fun `merges events from multiple detectors`() = runTest {
        val eventA = TriggerEvent(triggerA.id, elapsedRealtimeMillis = 1L)
        val eventB = TriggerEvent(triggerB.id, elapsedRealtimeMillis = 2L)
        val engine = TriggerEngine(
            TriggerRegistry(
                listOf(
                    FakeTriggerDetector(triggerA, listOf(eventA)),
                    FakeTriggerDetector(triggerB, listOf(eventB)),
                ),
            ),
            backgroundScope,
        )

        val received = mutableListOf<TriggerEvent>()
        backgroundScope.launch { engine.events.collect { received += it } }
        runCurrent()

        engine.start()
        runCurrent()

        assertEquals(setOf(eventA, eventB), received.toSet())
    }

    @Test
    fun `exposes per trigger state`() = runTest {
        val detector = FakeTriggerDetector(triggerA)
        val engine = TriggerEngine(TriggerRegistry(listOf(detector)), backgroundScope)
        runCurrent()

        assertEquals(mapOf(triggerA.id to TriggerState.Idle), engine.states.value)

        engine.start()
        runCurrent()

        assertEquals(mapOf(triggerA.id to TriggerState.Active), engine.states.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registry rejects duplicate trigger ids`() {
        TriggerRegistry(
            listOf(
                FakeTriggerDetector(FakeTrigger(TriggerId("dupe"))),
                FakeTriggerDetector(FakeTrigger(TriggerId("dupe"))),
            ),
        )
    }
}
