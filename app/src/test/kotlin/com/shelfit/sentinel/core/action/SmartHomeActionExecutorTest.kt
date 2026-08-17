package com.shelfit.sentinel.core.action

import com.shelfit.sentinel.core.FakeSmartHomeClient
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every condition the user asked to be handled, asserted without an account, a network or a
 * device.
 *
 * That is the return on putting the vendor SDK behind an interface: "what happens when the
 * lamp has been unplugged" is a unit test rather than an afternoon of unplugging a lamp.
 * Each case checks the *kind* of outcome, because the automation layer treats skipped,
 * failed and partial differently — a message alone would let a regression turn a failure
 * into a silent skip.
 */
class SmartHomeActionExecutorTest {

    private val event = TriggerEvent(
        triggerId = TriggerId.DoubleClap,
        elapsedRealtimeMillis = 1_000L,
        confidence = 0.9f,
    )

    private val lamp = DeviceId("lamp")
    private val plug = DeviceId("plug")

    private fun action(
        vararg targets: Pair<DeviceId, String>,
        command: SmartHomeCommand = SmartHomeCommand.TOGGLE,
    ) = SmartHomeDeviceAction(
        targets = targets.map { (id, name) -> SmartHomeTarget(id, name) },
        command = command,
    )

    @Test
    fun `a configured action switches every chosen device`() = runTest {
        val client = FakeSmartHomeClient()
        val executor = SmartHomeActionExecutor(client)

        val result = executor.execute(action(lamp to "Lamp", plug to "Plug"), event)

        assertEquals(ActionResult.Success, result)
        assertEquals(
            listOf(SmartHomeCommand.TOGGLE to listOf(lamp, plug)),
            client.commands,
        )
    }

    @Test
    fun `the executor only claims smart-home actions`() {
        val executor = SmartHomeActionExecutor(FakeSmartHomeClient())

        assertTrue(executor.canExecute(action(lamp to "Lamp")))
        assertTrue(!executor.canExecute(VibrateAction()))
        assertTrue(!executor.canExecute(DebugLogAction))
    }

    @Test
    fun `an action with no devices is skipped and nothing is sent`() = runTest {
        val client = FakeSmartHomeClient()

        val result = SmartHomeActionExecutor(client).execute(SmartHomeDeviceAction(), event)

        assertTrue("$result", result is ActionResult.Skipped)
        assertTrue("nothing should reach the provider", client.commands.isEmpty())
    }

    @Test
    fun `no provider in this build is a skip, not a failure`() = runTest {
        // A build without the SDK is a developer's situation, not a broken automation, and
        // the difference decides whether the user is told something is wrong.
        val client = FakeSmartHomeClient(
            initialState = SmartHomeState.NotConfigured,
            available = false,
        )

        val result = SmartHomeActionExecutor(client).execute(action(lamp to "Lamp"), event)

        assertTrue("$result", result is ActionResult.Skipped)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun `an account that was never linked is a skip`() = runTest {
        val client = FakeSmartHomeClient(initialState = SmartHomeState.NotConnected)

        val result = SmartHomeActionExecutor(client).execute(action(lamp to "Lamp"), event)

        assertTrue("$result", result is ActionResult.Skipped)
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun `a withdrawn permission is a failure, because it used to work`() = runTest {
        // The distinction that matters: the user set this up and it has stopped working.
        // Reporting it as "skipped" would hide a rule that no longer does anything.
        val client = FakeSmartHomeClient(initialState = SmartHomeState.PermissionRequired)

        val result = SmartHomeActionExecutor(client).execute(action(lamp to "Lamp"), event)

        assertTrue("$result", result is ActionResult.Failure)
        assertTrue(
            "the message should say how to fix it",
            (result as ActionResult.Failure).message.contains("reconnect", ignoreCase = true),
        )
        assertTrue(client.commands.isEmpty())
    }

    @Test
    fun `no network fails once rather than once per device`() = runTest {
        val client = FakeSmartHomeClient(
            initialState = SmartHomeState.Unavailable(
                SmartHomeFailure(SmartHomeFailure.Kind.NETWORK_UNAVAILABLE),
            ),
        )

        val result = SmartHomeActionExecutor(client)
            .execute(action(lamp to "Lamp", plug to "Plug"), event)

        assertTrue("$result", result is ActionResult.Failure)
        assertTrue(
            "a connection problem should not read as two hardware problems",
            (result as ActionResult.Failure).message.contains("network"),
        )
    }

    @Test
    fun `an unreachable home fails with the reason`() = runTest {
        val client = FakeSmartHomeClient(
            initialState = SmartHomeState.Unavailable(
                SmartHomeFailure(SmartHomeFailure.Kind.HOME_UNAVAILABLE),
            ),
        )

        val result = SmartHomeActionExecutor(client).execute(action(lamp to "Lamp"), event)

        assertTrue("$result", result is ActionResult.Failure)
    }

    @Test
    fun `an offline device fails and names itself`() = runTest {
        val client = FakeSmartHomeClient(
            outcome = { SmartHomeFailure(SmartHomeFailure.Kind.DEVICE_OFFLINE) },
        )

        val result = SmartHomeActionExecutor(client).execute(action(lamp to "Porch light"), event)

        assertTrue("$result", result is ActionResult.Failure)
        assertTrue(
            "the user needs to know which device",
            (result as ActionResult.Failure).message.contains("Porch light"),
        )
    }

    @Test
    fun `a device removed from the home is reported under the name the rule remembers`() =
        runTest {
            // The saved name is the only thing left to identify it by once it has gone.
            val client = FakeSmartHomeClient(
                outcome = { SmartHomeFailure(SmartHomeFailure.Kind.DEVICE_REMOVED) },
            )

            val result = SmartHomeActionExecutor(client)
                .execute(action(lamp to "Old lamp"), event)

            assertTrue("$result", result is ActionResult.Failure)
            assertTrue((result as ActionResult.Failure).message.contains("Old lamp"))
        }

    @Test
    fun `an unsupported device fails rather than being switched blindly`() = runTest {
        val client = FakeSmartHomeClient(
            outcome = { SmartHomeFailure(SmartHomeFailure.Kind.DEVICE_UNSUPPORTED) },
        )

        val result = SmartHomeActionExecutor(client)
            .execute(action(lamp to "Thermostat"), event)

        assertTrue("$result", result is ActionResult.Failure)
    }

    @Test
    fun `a toggle on a device with unknown state declines instead of guessing`() = runTest {
        val client = FakeSmartHomeClient(
            outcome = { SmartHomeFailure(SmartHomeFailure.Kind.DEVICE_STATE_UNKNOWN) },
        )

        val result = SmartHomeActionExecutor(client)
            .execute(action(lamp to "Old outlet", command = SmartHomeCommand.TOGGLE), event)

        assertTrue("$result", result is ActionResult.Failure)
        assertTrue(
            (result as ActionResult.Failure).message.contains("cannot tell if it is on"),
        )
    }

    @Test
    fun `a refused command fails`() = runTest {
        val client = FakeSmartHomeClient(
            outcome = { SmartHomeFailure(SmartHomeFailure.Kind.COMMAND_REJECTED) },
        )

        val result = SmartHomeActionExecutor(client).execute(action(lamp to "Lamp"), event)

        assertTrue("$result", result is ActionResult.Failure)
    }

    /**
     * The case the whole [ActionResult.Partial] branch exists for: one lamp of three is
     * unplugged, and reporting either "success" or "failure" would be a lie.
     */
    @Test
    fun `some devices working is reported as partial, with counts`() = runTest {
        val third = DeviceId("third")
        val client = FakeSmartHomeClient(
            outcome = { id ->
                if (id == plug) SmartHomeFailure(SmartHomeFailure.Kind.DEVICE_OFFLINE) else null
            },
        )

        val result = SmartHomeActionExecutor(client).execute(
            action(lamp to "Lamp", plug to "Plug", third to "Third"),
            event,
        )

        val partial = result as? ActionResult.Partial
        assertTrue("expected partial, got $result", partial != null)
        assertEquals(2, partial!!.succeeded)
        assertEquals(1, partial.failed)
        assertTrue(
            "the failing device should be named",
            partial.message.contains("Plug"),
        )
    }

    @Test
    fun `every device failing is a failure, not a partial success`() = runTest {
        val client = FakeSmartHomeClient(
            outcome = { SmartHomeFailure(SmartHomeFailure.Kind.DEVICE_OFFLINE) },
        )

        val result = SmartHomeActionExecutor(client)
            .execute(action(lamp to "Lamp", plug to "Plug"), event)

        assertTrue("$result", result is ActionResult.Failure)
    }

    @Test
    fun `the command asked for is the command sent`() = runTest {
        val client = FakeSmartHomeClient()
        val executor = SmartHomeActionExecutor(client)

        executor.execute(action(lamp to "Lamp", command = SmartHomeCommand.ON), event)
        executor.execute(action(lamp to "Lamp", command = SmartHomeCommand.OFF), event)

        assertEquals(
            listOf(SmartHomeCommand.ON, SmartHomeCommand.OFF),
            client.commands.map { it.first },
        )
    }
}
