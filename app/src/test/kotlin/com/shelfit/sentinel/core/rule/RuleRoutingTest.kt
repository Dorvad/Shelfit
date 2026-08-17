package com.shelfit.sentinel.core.rule

import com.shelfit.sentinel.core.MutableClock
import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.action.ActionExecutor
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.action.DebugLogAction
import com.shelfit.sentinel.core.action.ShowNotificationAction
import com.shelfit.sentinel.core.action.VibrateAction
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract this stage exists to establish:
 *
 * ```
 * DoubleClapDetected -> matching enabled rule -> the right ActionExecutor
 * ```
 *
 * and, just as important, that nothing else fires. These tests use a distinct recording
 * executor per action type, so "the right one ran" is asserted rather than inferred from
 * a single call count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleRoutingTest {

    private val events = MutableSharedFlow<TriggerEvent>()
    private val clock = MutableClock()

    /** Records only the action type it claims, so routing mistakes are visible. */
    private class TypedExecutor(private val type: String) : ActionExecutor {
        val performed = mutableListOf<TriggerEvent>()
        override fun canExecute(action: Action): Boolean = action.type == type
        override suspend fun execute(action: Action, event: TriggerEvent): ActionResult {
            performed += event
            return ActionResult.Success
        }
    }

    private val vibrate = TypedExecutor(VibrateAction().type)
    private val notify = TypedExecutor(ShowNotificationAction.type)
    private val log = TypedExecutor(DebugLogAction.type)

    private val dispatcher = ActionDispatcher(listOf(vibrate, notify, log))

    private fun doubleClap(confidence: Float = 0.9f) = TriggerEvent(
        triggerId = TriggerId.DoubleClap,
        elapsedRealtimeMillis = clock.now,
        confidence = confidence,
    )

    private fun rule(
        id: String,
        action: Action?,
        triggerId: TriggerId = TriggerId.DoubleClap,
        enabled: Boolean = true,
        minimumConfidence: Float = 0f,
        cooldownMillis: Long = 0L,
    ) = AutomationRule(
        id = id,
        name = id,
        triggerId = triggerId,
        action = action,
        enabled = enabled,
        minimumConfidence = minimumConfidence,
        cooldownMillis = cooldownMillis,
    )

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        rules: List<AutomationRule>,
    ): AutomationCoordinator = AutomationCoordinator(
        events = events,
        rules = MutableStateFlow(rules),
        dispatcher = dispatcher,
        clock = clock,
        scope = scope,
    ).also { it.start() }

    @Test
    fun `a double clap reaches the executor named by the matching rule`() = runTest {
        coordinator(backgroundScope, listOf(rule("vibrate", VibrateAction())))
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        assertEquals(1, vibrate.performed.size)
        assertTrue("only the named executor may run", notify.performed.isEmpty())
        assertTrue(log.performed.isEmpty())
    }

    @Test
    fun `each action type routes to its own executor`() = runTest {
        coordinator(
            backgroundScope,
            listOf(
                rule("a", VibrateAction()),
                rule("b", ShowNotificationAction),
                rule("c", DebugLogAction),
            ),
        )
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        assertEquals("vibrate", 1, vibrate.performed.size)
        assertEquals("notification", 1, notify.performed.size)
        assertEquals("log", 1, log.performed.size)
    }

    @Test
    fun `a disabled rule is ignored`() = runTest {
        coordinator(
            backgroundScope,
            listOf(rule("off", VibrateAction(), enabled = false)),
        )
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        assertTrue(vibrate.performed.isEmpty())
    }

    @Test
    fun `a disabled rule does not stop an enabled one beside it`() = runTest {
        coordinator(
            backgroundScope,
            listOf(
                rule("off", ShowNotificationAction, enabled = false),
                rule("on", VibrateAction()),
            ),
        )
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        assertEquals(1, vibrate.performed.size)
        assertTrue(notify.performed.isEmpty())
    }

    @Test
    fun `a rule for a different trigger is ignored`() = runTest {
        // A reserved identifier with no detector. A rule pointing at it must never fire
        // on some other trigger's event.
        coordinator(
            backgroundScope,
            listOf(rule("camera", VibrateAction(), triggerId = TriggerId.CameraMotion)),
        )
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        assertTrue(vibrate.performed.isEmpty())
    }

    @Test
    fun `only the rules matching the trigger fire`() = runTest {
        coordinator(
            backgroundScope,
            listOf(
                rule("clap", VibrateAction()),
                rule("light", ShowNotificationAction, triggerId = TriggerId.AmbientLight),
                rule("gesture", DebugLogAction, triggerId = TriggerId.HandGesture),
            ),
        )
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        assertEquals(1, vibrate.performed.size)
        assertTrue(notify.performed.isEmpty())
        assertTrue(log.performed.isEmpty())
    }

    @Test
    fun `a rule with no action set runs nothing and says why`() = runTest {
        val coordinator = coordinator(backgroundScope, listOf(rule("empty", action = null)))
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        assertTrue(vibrate.performed.isEmpty())
        assertEquals(
            ActionResult.Skipped("No action configured"),
            coordinator.outcomes.replayCache.last().result,
        )
    }

    @Test
    fun `a rule below its confidence floor is ignored`() = runTest {
        coordinator(
            backgroundScope,
            listOf(rule("picky", VibrateAction(), minimumConfidence = 0.95f)),
        )
        runCurrent()

        events.emit(doubleClap(confidence = 0.6f))
        runCurrent()
        assertTrue(vibrate.performed.isEmpty())

        events.emit(doubleClap(confidence = 0.99f))
        runCurrent()
        assertEquals(1, vibrate.performed.size)
    }

    @Test
    fun `an action with no registered executor is reported rather than swallowed`() = runTest {
        val unknown = object : Action {
            override val type: String = "smarthome.toggle"
            override val displayName: String = "Toggle living-room lights"
        }
        val coordinator = coordinator(backgroundScope, listOf(rule("future", unknown)))
        runCurrent()

        events.emit(doubleClap())
        runCurrent()

        val result = coordinator.outcomes.replayCache.last().result
        assertTrue("expected a skip, got $result", result is ActionResult.Skipped)
        assertTrue(
            (result as ActionResult.Skipped).reason.contains("smarthome.toggle"),
        )
    }

    @Test
    fun `each rule keeps its own cooldown`() = runTest {
        coordinator(
            backgroundScope,
            listOf(
                rule("slow", VibrateAction(), cooldownMillis = 5_000L),
                rule("fast", ShowNotificationAction, cooldownMillis = 0L),
            ),
        )
        runCurrent()

        events.emit(doubleClap())
        runCurrent()
        clock.now = 1_000L
        events.emit(doubleClap())
        runCurrent()

        assertEquals("still cooling down", 1, vibrate.performed.size)
        assertEquals("no cooldown of its own", 2, notify.performed.size)
    }

    @Test
    fun `the event reaches the executor intact`() = runTest {
        coordinator(backgroundScope, listOf(rule("vibrate", VibrateAction())))
        runCurrent()
        clock.now = 4_242L

        events.emit(doubleClap(confidence = 0.77f))
        runCurrent()

        val received = vibrate.performed.single()
        assertEquals(TriggerId.DoubleClap, received.triggerId)
        assertEquals(0.77f, received.confidence, 1e-6f)
        assertEquals(4_242L, received.elapsedRealtimeMillis)
    }
}
