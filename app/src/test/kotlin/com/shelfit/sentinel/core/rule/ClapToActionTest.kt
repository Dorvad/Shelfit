package com.shelfit.sentinel.core.rule

import com.shelfit.sentinel.core.FakeSensorStatusProvider
import com.shelfit.sentinel.core.MonotonicClock
import com.shelfit.sentinel.core.MutableClock
import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.action.ActionExecutor
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.action.ShowNotificationAction
import com.shelfit.sentinel.core.action.VibrateAction
import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.trigger.audio.DoubleClapConfiguration
import com.shelfit.sentinel.trigger.audio.DoubleClapDetector
import com.shelfit.sentinel.trigger.audio.SyntheticSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole chain on synthetic audio: two claps in a quiet room, through the real
 * detector, into a rule, out to the right executor.
 *
 * The unit tests either side of this prove the detector and the rule layer separately.
 * This one proves they are actually connected — and that the connection runs through the
 * rule layer rather than the detector reaching for an action itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClapToActionTest {

    private val audio = AudioCaptureConfig()
    private val clock = MutableClock()

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

    /** Quiet room, clap, pause, clap. */
    private fun doubleClapAudio() = SyntheticSignal(audio)
        .silence(700L)
        .clap()
        .silence(220L)
        .clap()
        .silence(500L)
        .toAudioInput()

    private fun detector(input: com.shelfit.sentinel.core.audio.AudioInput) = DoubleClapDetector(
        audioInput = input,
        sensorStatus = FakeSensorStatusProvider(
            mapOf(SensorKind.MICROPHONE to SensorAvailability.AVAILABLE),
        ),
        clock = MonotonicClock { clock.now },
    )

    /**
     * Wires detector -> events -> coordinator -> dispatcher, exactly as `AppContainer`
     * does, and drives it with a fixed audio clip.
     *
     * The coordinator is allowed to subscribe before the detector starts, which is not
     * test ceremony: the engine's event flow has no replay, so an event emitted before
     * anything is listening is dropped. In the app that ordering is structural — the
     * coordinator starts in `AppContainer`'s initialiser, long before any detector can
     * run — and reproducing it here is what makes this test represent reality.
     */
    private fun TestScope.wire(rules: List<AutomationRule>) {
        val events = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 8)

        AutomationCoordinator(
            events = events,
            rules = MutableStateFlow(rules),
            dispatcher = ActionDispatcher(listOf(vibrate, notify)),
            clock = clock,
            scope = backgroundScope,
        ).start()
        runCurrent()

        backgroundScope.launch {
            detector(doubleClapAudio())
                .events(DoubleClapConfiguration())
                .collect { events.emit(it) }
        }
        runCurrent()
    }

    private fun rule(
        action: Action?,
        triggerId: TriggerId = TriggerId.DoubleClap,
        enabled: Boolean = true,
    ) = AutomationRule(
        id = "rule.$triggerId.${action?.type}",
        name = "test",
        triggerId = triggerId,
        action = action,
        enabled = enabled,
    )

    @Test
    fun `a detected double clap performs the action its rule names`() = runTest {
        wire(listOf(rule(VibrateAction())))

        assertEquals(
            "the clap should have travelled detector -> rule -> executor",
            1,
            vibrate.performed.size,
        )
        assertEquals(TriggerId.DoubleClap, vibrate.performed.single().triggerId)
        assertTrue(notify.performed.isEmpty())
    }

    @Test
    fun `a detected double clap performs every enabled rule attached to it`() = runTest {
        wire(listOf(rule(VibrateAction()), rule(ShowNotificationAction)))

        assertEquals(1, vibrate.performed.size)
        assertEquals(1, notify.performed.size)
    }

    @Test
    fun `a disabled rule means a detected clap does nothing`() = runTest {
        wire(listOf(rule(VibrateAction(), enabled = false)))

        assertTrue(vibrate.performed.isEmpty())
    }

    @Test
    fun `a rule on another trigger is untouched by a clap`() = runTest {
        wire(listOf(rule(VibrateAction(), triggerId = TriggerId.AmbientLight)))

        assertTrue(vibrate.performed.isEmpty())
    }

    @Test
    fun `with no rules at all a clap is still detected and simply does nothing`() = runTest {
        // Proves the detector does not depend on a rule existing — it reports, and the
        // rule layer decides. A detector that needed a rule would be coupled to one.
        val events = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 8)
        val seen = mutableListOf<TriggerEvent>()
        backgroundScope.launch { events.collect { seen += it } }
        runCurrent()

        AutomationCoordinator(
            events = events,
            rules = MutableStateFlow(emptyList()),
            dispatcher = ActionDispatcher(listOf(vibrate, notify)),
            clock = clock,
            scope = backgroundScope,
        ).start()

        backgroundScope.launch {
            detector(doubleClapAudio())
                .events(DoubleClapConfiguration())
                .collect { events.emit(it) }
        }
        runCurrent()

        assertEquals("the detection still happened", 1, seen.size)
        assertTrue("but nothing acted on it", vibrate.performed.isEmpty())
    }

    @Test
    fun `the confidence the detector measured is what the rule layer sees`() = runTest {
        wire(listOf(rule(VibrateAction())))

        val confidence = vibrate.performed.single().confidence
        assertTrue("confidence was $confidence", confidence in 0.5f..1f)
    }
}
