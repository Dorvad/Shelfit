package com.shelfit.sentinel.core.rule

import com.shelfit.sentinel.core.FakeSensorStatusProvider
import com.shelfit.sentinel.core.MonotonicClock
import com.shelfit.sentinel.core.MutableClock
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.action.SmartHomeActionExecutor
import com.shelfit.sentinel.core.action.SmartHomeDeviceAction
import com.shelfit.sentinel.core.action.SmartHomeTarget
import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioInput
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.platform.smarthome.SimulatedSmartHomeClient
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
 * The stage's target experience, end to end: two claps on synthetic audio switch a device.
 *
 * Deliberately built from the real detector, the real coordinator, the real executor and a
 * simulated provider — the only pretend part is the home. What it proves is that the chain is
 * connected and that the detector reaches a light without knowing a light exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClapToSmartHomeTest {

    private val audio = AudioCaptureConfig()
    private val clock = MutableClock()
    private val lamp = DeviceId("sim.lamp.living")

    private val outcomes = mutableListOf<AutomationOutcome>()

    /** Quiet room, clap, pause, clap. */
    private fun doubleClapAudio(): AudioInput = SyntheticSignal(audio)
        .silence(700L)
        .clap()
        .silence(220L)
        .clap()
        .silence(500L)
        .toAudioInput()

    private fun detector(input: AudioInput) = DoubleClapDetector(
        audioInput = input,
        sensorStatus = FakeSensorStatusProvider(
            mapOf(SensorKind.MICROPHONE to SensorAvailability.AVAILABLE),
        ),
        clock = MonotonicClock { clock.now },
    )

    private fun rule(action: SmartHomeDeviceAction) = AutomationRule(
        id = "rule.smarthome",
        name = "Double clap → smart home",
        triggerId = TriggerId.DoubleClap,
        action = action,
    )

    /** Coordinator first, then the detector — the ordering `AppContainer` establishes. */
    private fun TestScope.wire(
        client: SimulatedSmartHomeClient,
        rules: List<AutomationRule>,
    ) {
        val events = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 8)

        val coordinator = AutomationCoordinator(
            events = events,
            rules = MutableStateFlow(rules),
            dispatcher = ActionDispatcher(listOf(SmartHomeActionExecutor(client))),
            clock = clock,
            scope = backgroundScope,
        )
        backgroundScope.launch { coordinator.outcomes.collect { outcomes += it } }
        coordinator.start()
        runCurrent()

        backgroundScope.launch {
            detector(doubleClapAudio())
                .events(DoubleClapConfiguration())
                .collect { events.emit(it) }
        }
        runCurrent()
    }

    private suspend fun SimulatedSmartHomeClient.lampIsOn(): Boolean? {
        val structureId = (state.value as SmartHomeState.Connected).selected!!
        val devices = (devices(structureId) as SmartHomeResult.Success).value
        return devices.first { it.id == lamp }.isOn
    }

    @Test
    fun `a double clap toggles the chosen device`() = runTest {
        val client = SimulatedSmartHomeClient()
        client.connect()
        assertEquals("the lamp starts off", false, client.lampIsOn())

        wire(
            client,
            listOf(
                rule(
                    SmartHomeDeviceAction(
                        targets = listOf(SmartHomeTarget(lamp, "Living room lamp")),
                        command = SmartHomeCommand.TOGGLE,
                    ),
                ),
            ),
        )

        assertEquals("the clap should have switched it", true, client.lampIsOn())
        assertEquals(ActionResult.Success, outcomes.single().result)
    }

    @Test
    fun `two devices are switched by one clap`() = runTest {
        val plug = DeviceId("sim.plug.kettle")
        val client = SimulatedSmartHomeClient()
        client.connect()

        wire(
            client,
            listOf(
                rule(
                    SmartHomeDeviceAction(
                        targets = listOf(
                            SmartHomeTarget(lamp, "Living room lamp"),
                            SmartHomeTarget(plug, "Kettle plug"),
                        ),
                        command = SmartHomeCommand.ON,
                    ),
                ),
            ),
        )

        val structureId = (client.state.value as SmartHomeState.Connected).selected!!
        val devices = (client.devices(structureId) as SmartHomeResult.Success).value
        assertEquals(true, devices.first { it.id == lamp }.isOn)
        assertEquals(true, devices.first { it.id == plug }.isOn)
    }

    @Test
    fun `one unreachable device out of two is reported as partial rather than failing both`() =
        runTest {
            val offline = DeviceId("sim.lamp.offline")
            val client = SimulatedSmartHomeClient()
            client.connect()

            wire(
                client,
                listOf(
                    rule(
                        SmartHomeDeviceAction(
                            targets = listOf(
                                SmartHomeTarget(lamp, "Living room lamp"),
                                SmartHomeTarget(offline, "Porch light"),
                            ),
                            command = SmartHomeCommand.ON,
                        ),
                    ),
                ),
            )

            val result = outcomes.single().result as? ActionResult.Partial
            assertTrue("expected partial, got ${outcomes.single().result}", result != null)
            assertEquals(1, result!!.succeeded)
            assertEquals(1, result.failed)
            assertEquals("the reachable one still switched", true, client.lampIsOn())
        }

    @Test
    fun `a clap with no smart home connected leaves the rule reporting honestly`() = runTest {
        // The state the app ships in until an SDK is present: the detection happens, the
        // automation runs, and the outcome says why nothing was switched.
        val client = SimulatedSmartHomeClient()

        wire(
            client,
            listOf(
                rule(
                    SmartHomeDeviceAction(
                        targets = listOf(SmartHomeTarget(lamp, "Living room lamp")),
                    ),
                ),
            ),
        )

        val result = outcomes.single().result
        assertTrue("$result", result is ActionResult.Skipped)
    }

    @Test
    fun `permission withdrawn between saving the rule and clapping is reported as a failure`() =
        runTest {
            val client = SimulatedSmartHomeClient()
            client.connect()
            client.setFault(SimulatedSmartHomeClient.Fault.PERMISSION_REVOKED)

            wire(
                client,
                listOf(
                    rule(
                        SmartHomeDeviceAction(
                            targets = listOf(SmartHomeTarget(lamp, "Living room lamp")),
                        ),
                    ),
                ),
            )

            val result = outcomes.single().result
            assertTrue("$result", result is ActionResult.Failure)
        }
}
