package com.shelfit.sentinel.platform.smarthome

import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulator is a test fixture that users can also reach, so its behaviour is asserted
 * like anything else.
 *
 * These tests are also the executable statement of the contract [GoogleHomeClient] will have
 * to satisfy: never throw, one result per requested device, and decline a toggle it cannot
 * determine. A real implementation that passes the equivalent of these is behaving correctly.
 */
class SimulatedSmartHomeClientTest {

    // Ids from the simulator's seed data, each chosen for the case it covers.
    private val livingRoomLamp = "sim.lamp.living"
    private val statelessOutlet = "sim.plug.stateless"
    private val offlineLight = "sim.lamp.offline"
    private val thermostat = "sim.thermostat"

    private suspend fun connected(): SimulatedSmartHomeClient =
        SimulatedSmartHomeClient().also { it.connect() }

    /** Device ids paired with the name a rule would have remembered for them. */
    private fun targets(vararg ids: String) = ids.map { DeviceId(it) to it }

    @Test
    fun `it starts disconnected, so nothing appears linked without being asked`() {
        assertEquals(SmartHomeState.NotConnected, SimulatedSmartHomeClient().state.value)
    }

    @Test
    fun `connecting produces homes to choose between`() = runTest {
        val client = connected()

        val state = client.state.value as SmartHomeState.Connected
        assertTrue(state.structures.size > 1)
        assertEquals(state.structures.first().id, state.selected)
    }

    @Test
    fun `devices cannot be listed before connecting`() = runTest {
        val structureId = (connected().state.value as SmartHomeState.Connected).selected!!

        val result = SimulatedSmartHomeClient().devices(structureId)

        assertEquals(
            SmartHomeFailure.Kind.NOT_CONNECTED,
            (result as SmartHomeResult.Failure).failure.kind,
        )
    }

    @Test
    fun `the listed home includes devices this app cannot switch`() = runTest {
        val client = connected()
        val structureId = (client.state.value as SmartHomeState.Connected).selected!!

        val devices = (client.devices(structureId) as SmartHomeResult.Success).value

        // Surfaced rather than filtered: a user who cannot find their thermostat in the list
        // does not learn that it was found but is unsupported.
        assertTrue(devices.any { !it.supported })
        assertTrue(devices.any { !it.reachable })
        assertTrue(devices.any { !it.stateKnown })
    }

    @Test
    fun `switching a device on changes what the list reports`() = runTest {
        val client = connected()
        val structureId = (client.state.value as SmartHomeState.Connected).selected!!

        client.execute(SmartHomeCommand.ON, targets(livingRoomLamp))
        val devices = (client.devices(structureId) as SmartHomeResult.Success).value

        assertEquals(true, devices.first { it.id == DeviceId(livingRoomLamp) }.isOn)
    }

    @Test
    fun `a toggle sends the opposite of the current state`() = runTest {
        val client = connected()
        val structureId = (client.state.value as SmartHomeState.Connected).selected!!

        client.execute(SmartHomeCommand.TOGGLE, targets(livingRoomLamp))
        val afterFirst = (client.devices(structureId) as SmartHomeResult.Success)
            .value.first { it.id == DeviceId(livingRoomLamp) }.isOn
        client.execute(SmartHomeCommand.TOGGLE, targets(livingRoomLamp))
        val afterSecond = (client.devices(structureId) as SmartHomeResult.Success)
            .value.first { it.id == DeviceId(livingRoomLamp) }.isOn

        assertEquals(true, afterFirst)
        assertEquals(false, afterSecond)
    }

    @Test
    fun `a toggle on a device with no readable state declines`() = runTest {
        val report = connected().execute(SmartHomeCommand.TOGGLE, targets(statelessOutlet))

        assertEquals(
            SmartHomeFailure.Kind.DEVICE_STATE_UNKNOWN,
            report.results.single().failure?.kind,
        )
    }

    @Test
    fun `on and off work on a device whose state cannot be read`() = runTest {
        // Only toggle needs the current state. Refusing on and off too would be needlessly
        // restrictive for an older plug.
        val report = connected().execute(SmartHomeCommand.ON, targets(statelessOutlet))

        assertTrue(report.allSucceeded)
    }

    @Test
    fun `an offline device fails as offline`() = runTest {
        val report = connected().execute(SmartHomeCommand.ON, targets(offlineLight))

        assertEquals(
            SmartHomeFailure.Kind.DEVICE_OFFLINE,
            report.results.single().failure?.kind,
        )
    }

    @Test
    fun `an unsupported device is refused rather than attempted`() = runTest {
        val report = connected().execute(SmartHomeCommand.ON, targets(thermostat))

        assertEquals(
            SmartHomeFailure.Kind.DEVICE_UNSUPPORTED,
            report.results.single().failure?.kind,
        )
    }

    @Test
    fun `a device that is not in the home is reported as removed`() = runTest {
        val report = connected()
            .execute(SmartHomeCommand.ON, listOf(DeviceId("sim.gone") to "Old lamp"))

        val result = report.results.single()
        assertEquals(SmartHomeFailure.Kind.DEVICE_REMOVED, result.failure?.kind)
        assertEquals("the remembered name is all that is left", "Old lamp", result.deviceName)
    }

    @Test
    fun `a mixed selection reports per device`() = runTest {
        val report = connected()
            .execute(SmartHomeCommand.ON, targets(livingRoomLamp, offlineLight))

        assertTrue(report.partial)
        assertEquals(1, report.succeeded.size)
        assertEquals(1, report.failed.size)
        // The summary names the device as the home calls it, not by the id a rule stored —
        // "Porch light: offline" is what a person can act on.
        assertTrue(report.summarise(), report.summarise().contains("Porch light"))
    }

    @Test
    fun `every requested device gets a result, whatever happened`() = runTest {
        val requested =
            targets(livingRoomLamp, offlineLight, thermostat, statelessOutlet)

        val report = connected().execute(SmartHomeCommand.TOGGLE, requested)

        // A missing entry would be counted as a success by the executor's arithmetic.
        assertEquals(requested.size, report.results.size)
        assertEquals(requested.map { it.first }, report.results.map { it.deviceId })
    }

    @Test
    fun `a withdrawn permission shows in the state without anything being called`() {
        val client = SimulatedSmartHomeClient()

        client.setFault(SimulatedSmartHomeClient.Fault.PERMISSION_REVOKED)

        // The UI observes state, so a permission lost while the app was in the background
        // has to appear there rather than only at the next command.
        assertEquals(SmartHomeState.PermissionRequired, client.state.value)
    }

    @Test
    fun `no network fails every device without touching any of them`() = runTest {
        val client = connected()
        client.setFault(SimulatedSmartHomeClient.Fault.NETWORK_UNAVAILABLE)

        val report =
            client.execute(SmartHomeCommand.ON, targets(livingRoomLamp, statelessOutlet))

        assertTrue(report.allFailed)
        assertTrue(
            report.results.all {
                it.failure?.kind == SmartHomeFailure.Kind.NETWORK_UNAVAILABLE
            },
        )
    }

    @Test
    fun `an unavailable home fails to list devices`() = runTest {
        val client = connected()
        val structureId = (client.state.value as SmartHomeState.Connected).selected!!
        client.setFault(SimulatedSmartHomeClient.Fault.HOME_UNAVAILABLE)

        val result = client.devices(structureId)

        assertEquals(
            SmartHomeFailure.Kind.HOME_UNAVAILABLE,
            (result as SmartHomeResult.Failure).failure.kind,
        )
    }

    @Test
    fun `a rejected command is distinguishable from an unreachable device`() = runTest {
        val client = connected()
        client.setFault(SimulatedSmartHomeClient.Fault.COMMANDS_REJECTED)

        val report = client.execute(SmartHomeCommand.ON, targets(livingRoomLamp))

        assertEquals(
            SmartHomeFailure.Kind.COMMAND_REJECTED,
            report.results.single().failure?.kind,
        )
    }

    @Test
    fun `clearing a fault restores normal behaviour`() = runTest {
        val client = connected()
        client.setFault(SimulatedSmartHomeClient.Fault.PERMISSION_REVOKED)
        client.setFault(SimulatedSmartHomeClient.Fault.NONE)
        client.connect()

        assertTrue(client.state.value.isConnected)
        assertTrue(client.execute(SmartHomeCommand.ON, targets(livingRoomLamp)).allSucceeded)
    }

    @Test
    fun `choosing the other home changes which devices are listed`() = runTest {
        val client = connected()
        val structures = (client.state.value as SmartHomeState.Connected).structures

        client.selectStructure(structures[1].id)
        val devices = (client.devices(structures[1].id) as SmartHomeResult.Success).value

        assertEquals(structures[1].id, (client.state.value as SmartHomeState.Connected).selected)
        assertTrue(devices.none { it.id == DeviceId(livingRoomLamp) })
    }
}
