package com.shelfit.sentinel.core.action

import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The action's own encoding.
 *
 * It owns both halves — [SmartHomeDeviceAction.parameters] and
 * [SmartHomeDeviceAction.fromParameters] — so that neither the codec nor the rule editor has
 * to know its keys. That only holds if the two agree, which is what these assert.
 */
class SmartHomeDeviceActionTest {

    private fun action(
        vararg names: Pair<String, String>,
        command: SmartHomeCommand = SmartHomeCommand.TOGGLE,
    ) = SmartHomeDeviceAction(
        targets = names.map { (id, name) -> SmartHomeTarget(DeviceId(id), name) },
        command = command,
    )

    private fun roundTrip(action: SmartHomeDeviceAction) =
        SmartHomeDeviceAction.fromParameters(action.parameters)

    @Test
    fun `devices and command survive a round trip`() {
        val original = action(
            "lamp" to "Living room lamp",
            "plug" to "Kettle plug",
            command = SmartHomeCommand.ON,
        )

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `device order is preserved`() {
        // Order is the user's choice, and a report reading in a different order than the
        // editor showed would be confusing for no reason.
        val original = action("c" to "Third", "a" to "First", "b" to "Second")

        assertEquals(
            listOf("Third", "First", "Second"),
            roundTrip(original).targets.map { it.name },
        )
    }

    @Test
    fun `an action with no devices round trips as unconfigured`() {
        val decoded = roundTrip(SmartHomeDeviceAction())

        assertTrue(decoded.targets.isEmpty())
        assertTrue(!decoded.configured)
    }

    @Test
    fun `an unrecognised command falls back without discarding the devices`() {
        // What a downgrade looks like. Losing the device list would be far worse than
        // running the wrong verb, which the user can see and correct in the editor.
        val decoded = SmartHomeDeviceAction.fromParameters(
            mapOf("command" to "DIM_TO_HALF", "device.0" to "lamp", "name.0" to "Lamp"),
        )

        assertEquals(SmartHomeCommand.TOGGLE, decoded.command)
        assertEquals(listOf("Lamp"), decoded.targets.map { it.name })
    }

    @Test
    fun `a device with no stored name is still usable`() {
        val decoded = SmartHomeDeviceAction.fromParameters(mapOf("device.0" to "lamp"))

        assertEquals(DeviceId("lamp"), decoded.targets.single().deviceId)
        assertTrue(decoded.targets.single().name.isNotBlank())
    }

    @Test
    fun `an entry with no device id is dropped rather than creating a nameless target`() {
        val decoded = SmartHomeDeviceAction.fromParameters(
            mapOf("device.0" to "", "name.0" to "Ghost", "device.1" to "lamp", "name.1" to "Lamp"),
        )

        assertEquals(listOf("Lamp"), decoded.targets.map { it.name })
    }

    @Test
    fun `the label describes what the automation will actually do`() {
        assertEquals("Turn on Lamp", action("a" to "Lamp", command = SmartHomeCommand.ON).displayName)
        assertEquals("Toggle 2 devices", action("a" to "Lamp", "b" to "Plug").displayName)
        assertTrue(SmartHomeDeviceAction().displayName.isNotBlank())
    }

    @Test
    fun `the catalogue rebuilds the action from its parameters`() {
        val original = action("lamp" to "Living room lamp", command = SmartHomeCommand.OFF)

        val rebuilt = ActionCatalogue.WithSmartHome
            .action(SmartHomeDeviceAction.TYPE, original.parameters)

        assertEquals(original, rebuilt)
    }

    @Test
    fun `the smart-home kind is the one that needs configuring`() {
        val kinds = ActionCatalogue.WithSmartHome.kinds

        assertEquals(
            listOf(SmartHomeDeviceAction.TYPE),
            kinds.filter { it.requiresConfiguration }.map { it.type },
        )
    }

    @Test
    fun `a build without a smart-home provider does not offer the action`() {
        assertTrue(
            ActionCatalogue.LocalDebug.kind(SmartHomeDeviceAction.TYPE) == null,
        )
    }
}
