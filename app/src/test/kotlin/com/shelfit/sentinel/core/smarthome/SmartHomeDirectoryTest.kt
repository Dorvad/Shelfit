package com.shelfit.sentinel.core.smarthome

import com.shelfit.sentinel.core.FakeSmartHomeClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared device list.
 *
 * Its whole job is to be correct about *not* having devices: an empty list shown as though it
 * were loaded is how a user concludes their lamp is gone when the real answer is "no network".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartHomeDirectoryTest {

    private val home = StructureId("home")

    private fun device(id: String, kind: DeviceKind = DeviceKind.LIGHT) = SmartHomeDevice(
        id = DeviceId(id),
        name = id,
        roomName = null,
        kind = kind,
        reachable = true,
        isOn = false,
    )

    @Test
    fun `connecting loads the chosen home without being asked`() = runTest {
        val client = FakeSmartHomeClient(
            initialState = SmartHomeState.NotConnected,
            devices = listOf(device("lamp")),
        )
        val directory = SmartHomeDirectory(client, backgroundScope)
        runCurrent()

        assertTrue("nothing to load while disconnected", directory.devices.value.devices.isEmpty())

        client.emit(SmartHomeState.Connected(listOf(SmartHomeStructure(home, "Home"))))
        runCurrent()

        assertEquals(listOf(DeviceId("lamp")), directory.devices.value.devices.map { it.id })
        assertTrue(directory.devices.value.loaded)
    }

    @Test
    fun `losing access empties the list rather than leaving stale devices on screen`() = runTest {
        val client = FakeSmartHomeClient(devices = listOf(device("lamp")))
        val directory = SmartHomeDirectory(client, backgroundScope)
        runCurrent()
        assertEquals(1, directory.devices.value.devices.size)

        client.emit(SmartHomeState.PermissionRequired)
        runCurrent()

        // Leaving them listed would invite taps that cannot work.
        assertTrue(directory.devices.value.devices.isEmpty())
        assertTrue(!directory.devices.value.loaded)
    }

    @Test
    fun `a failure to load is distinguishable from a home with no devices`() = runTest {
        val client = FakeSmartHomeClient(
            devicesFailure = SmartHomeFailure(SmartHomeFailure.Kind.NETWORK_UNAVAILABLE),
        )
        val directory = SmartHomeDirectory(client, backgroundScope)
        runCurrent()

        val state = directory.devices.value
        assertEquals(SmartHomeFailure.Kind.NETWORK_UNAVAILABLE, state.failure?.kind)
        assertTrue("a failure is not a loaded empty home", !state.loaded)
    }

    @Test
    fun `only lights and outlets are offered for selection`() = runTest {
        val client = FakeSmartHomeClient(
            devices = listOf(
                device("lamp", DeviceKind.LIGHT),
                device("plug", DeviceKind.OUTLET),
                device("thermostat", DeviceKind.UNSUPPORTED),
            ),
        )
        val directory = SmartHomeDirectory(client, backgroundScope)
        runCurrent()

        val state = directory.devices.value
        assertEquals(3, state.devices.size)
        assertEquals(
            "the unsupported one is listed but not selectable",
            listOf(DeviceId("lamp"), DeviceId("plug")),
            state.selectable.map { it.id },
        )
    }

    @Test
    fun `choosing another home replaces the list`() = runTest {
        val other = StructureId("other")
        val client = FakeSmartHomeClient(
            initialState = SmartHomeState.Connected(
                structures = listOf(
                    SmartHomeStructure(home, "Home"),
                    SmartHomeStructure(other, "Other"),
                ),
            ),
            devices = listOf(device("lamp")),
        )
        val directory = SmartHomeDirectory(client, backgroundScope)
        runCurrent()

        client.selectStructure(other)
        runCurrent()

        assertEquals(other, directory.devices.value.structureId)
    }
}
