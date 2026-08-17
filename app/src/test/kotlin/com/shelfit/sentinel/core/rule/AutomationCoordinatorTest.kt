package com.shelfit.sentinel.core.rule

import com.shelfit.sentinel.core.MutableClock
import com.shelfit.sentinel.core.RecordingActionExecutor
import com.shelfit.sentinel.core.ThrowingActionExecutor
import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.action.DebugLogAction
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationCoordinatorTest {

    private val triggerId = TriggerId("test.clap")
    private val events = MutableSharedFlow<TriggerEvent>()
    private val clock = MutableClock()

    private fun event(confidence: Float = 1f) =
        TriggerEvent(triggerId, elapsedRealtimeMillis = clock.now, confidence = confidence)

    private fun rule(
        action: Action? = DebugLogAction,
        enabled: Boolean = true,
        minimumConfidence: Float = 0f,
        cooldownMillis: Long = 0L,
    ) = AutomationRule(
        id = "rule.1",
        name = "Test rule",
        triggerId = triggerId,
        action = action,
        enabled = enabled,
        minimumConfidence = minimumConfidence,
        cooldownMillis = cooldownMillis,
    )

    private fun coordinator(
        scope: CoroutineScope,
        rules: List<AutomationRule>,
        executor: RecordingActionExecutor = RecordingActionExecutor(),
    ): Pair<AutomationCoordinator, RecordingActionExecutor> {
        val coordinator = AutomationCoordinator(
            events = events,
            rules = MutableStateFlow(rules),
            dispatcher = ActionDispatcher(listOf(executor)),
            clock = clock,
            scope = scope,
        )
        coordinator.start()
        return coordinator to executor
    }

    @Test
    fun `dispatches the configured action`() = runTest {
        val (coordinator, executor) = coordinator(backgroundScope, listOf(rule()))
        runCurrent()

        events.emit(event())
        runCurrent()

        assertEquals(1, executor.executed.size)
        assertEquals(DebugLogAction, executor.executed.single().first)
        assertEquals(ActionResult.Success, coordinator.outcomes.replayCache.last().result)
    }

    @Test
    fun `skips a rule with no action configured`() = runTest {
        val (coordinator, executor) = coordinator(backgroundScope, listOf(rule(action = null)))
        runCurrent()

        events.emit(event())
        runCurrent()

        assertTrue(executor.executed.isEmpty())
        val result = coordinator.outcomes.replayCache.last().result
        assertEquals(ActionResult.Skipped("No action configured"), result)
    }

    @Test
    fun `ignores events for other triggers`() = runTest {
        val (_, executor) = coordinator(backgroundScope, listOf(rule()))
        runCurrent()

        events.emit(TriggerEvent(TriggerId("test.other"), elapsedRealtimeMillis = 0L))
        runCurrent()

        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun `ignores a disabled rule`() = runTest {
        val (_, executor) = coordinator(backgroundScope, listOf(rule(enabled = false)))
        runCurrent()

        events.emit(event())
        runCurrent()

        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun `ignores events below the minimum confidence`() = runTest {
        val (_, executor) = coordinator(
            backgroundScope,
            listOf(rule(minimumConfidence = 0.8f)),
        )
        runCurrent()

        events.emit(event(confidence = 0.5f))
        runCurrent()
        assertTrue(executor.executed.isEmpty())

        events.emit(event(confidence = 0.9f))
        runCurrent()
        assertEquals(1, executor.executed.size)
    }

    @Test
    fun `suppresses a second firing inside the cooldown window`() = runTest {
        val (_, executor) = coordinator(
            backgroundScope,
            listOf(rule(cooldownMillis = 1_000L)),
        )
        runCurrent()

        events.emit(event())
        runCurrent()
        assertEquals(1, executor.executed.size)

        clock.now = 500L
        events.emit(event())
        runCurrent()
        assertEquals("still inside cooldown", 1, executor.executed.size)

        clock.now = 1_500L
        events.emit(event())
        runCurrent()
        assertEquals("cooldown elapsed", 2, executor.executed.size)
    }

    @Test
    fun `a failing executor does not stop the pipeline`() = runTest {
        val dispatcher = ActionDispatcher(
            listOf(ThrowingActionExecutor(IllegalStateException("boom"))),
        )
        val coordinator = AutomationCoordinator(
            events = events,
            rules = MutableStateFlow(listOf(rule())),
            dispatcher = dispatcher,
            clock = clock,
            scope = backgroundScope,
        )
        coordinator.start()
        runCurrent()

        events.emit(event())
        runCurrent()
        events.emit(event())
        runCurrent()

        assertEquals(2, coordinator.outcomes.replayCache.size)
        assertEquals(ActionResult.Failure("boom"), coordinator.outcomes.replayCache.last().result)
    }

    @Test
    fun `reports a skip when no executor handles the action`() = runTest {
        val (coordinator, _) = coordinator(
            scope = backgroundScope,
            rules = listOf(rule()),
            executor = RecordingActionExecutor(accepts = { false }),
        )
        runCurrent()

        events.emit(event())
        runCurrent()

        val result = coordinator.outcomes.replayCache.last().result
        assertTrue(result is ActionResult.Skipped)
    }
}
