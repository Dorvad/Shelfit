package com.shelfit.sentinel.platform.smarthome

import com.shelfit.sentinel.core.FakeSmartHomeClient
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeProvider
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider routing.
 *
 * The reason this is worth testing separately: the executor reads `state.value` synchronously
 * before deciding whether to attempt a command, so a router that published the wrong state —
 * or published it late — would make automations refuse work they could have done, with no
 * error anywhere to explain it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectableSmartHomeClientTest {

    private val lamp = DeviceId("lamp")

    private fun router(
        chosen: MutableStateFlow<SmartHomeProvider>,
        clients: Map<SmartHomeProvider, com.shelfit.sentinel.core.smarthome.SmartHomeClient>,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SelectableSmartHomeClient(clients = clients, chosen = chosen, scope = scope)

    @Test
    fun `commands go to the chosen provider and nowhere else`() = runTest {
        val tuya = FakeSmartHomeClient()
        val simulated = FakeSmartHomeClient()
        val chosen = MutableStateFlow(SmartHomeProvider.TUYA)

        val client = router(
            chosen,
            mapOf(SmartHomeProvider.TUYA to tuya, SmartHomeProvider.SIMULATED to simulated),
            backgroundScope,
        )
        runCurrent()

        client.execute(SmartHomeCommand.ON, listOf(lamp to "Lamp"))

        assertEquals(1, tuya.commands.size)
        assertTrue("the other provider must not be touched", simulated.commands.isEmpty())
    }

    @Test
    fun `changing provider redirects the next command`() = runTest {
        val tuya = FakeSmartHomeClient()
        val simulated = FakeSmartHomeClient()
        val chosen = MutableStateFlow(SmartHomeProvider.TUYA)

        val client = router(
            chosen,
            mapOf(SmartHomeProvider.TUYA to tuya, SmartHomeProvider.SIMULATED to simulated),
            backgroundScope,
        )
        runCurrent()

        client.execute(SmartHomeCommand.ON, listOf(lamp to "Lamp"))
        chosen.value = SmartHomeProvider.SIMULATED
        runCurrent()
        client.execute(SmartHomeCommand.OFF, listOf(lamp to "Lamp"))

        assertEquals(1, tuya.commands.size)
        assertEquals(1, simulated.commands.size)
    }

    @Test
    fun `the chosen provider's state is what the executor reads`() = runTest {
        val tuya = FakeSmartHomeClient(initialState = SmartHomeState.NotConnected)
        val chosen = MutableStateFlow(SmartHomeProvider.TUYA)

        val client = router(chosen, mapOf(SmartHomeProvider.TUYA to tuya), backgroundScope)
        runCurrent()

        assertEquals(SmartHomeState.NotConnected, client.state.value)

        tuya.emit(SmartHomeState.PermissionRequired)
        runCurrent()

        assertEquals(SmartHomeState.PermissionRequired, client.state.value)
    }

    @Test
    fun `choosing no smart home reports not configured rather than crashing`() = runTest {
        // NONE has no entry in the map on purpose. A null check in every caller would be the
        // alternative, and it would be forgotten somewhere.
        val chosen = MutableStateFlow(SmartHomeProvider.NONE)
        val client = router(
            chosen,
            mapOf(SmartHomeProvider.TUYA to FakeSmartHomeClient()),
            backgroundScope,
        )
        runCurrent()

        assertEquals(SmartHomeState.NotConfigured, client.state.value)

        val report = client.execute(SmartHomeCommand.ON, listOf(lamp to "Lamp"))

        assertTrue(report.allFailed)
        assertEquals(
            SmartHomeFailure.Kind.NOT_CONFIGURED,
            report.results.single().failure?.kind,
        )
    }

    @Test
    fun `a provider this build does not supply behaves like none`() = runTest {
        val chosen = MutableStateFlow(SmartHomeProvider.GOOGLE)
        val client = router(chosen, emptyMap(), backgroundScope)
        runCurrent()

        assertEquals(SmartHomeState.NotConfigured, client.state.value)
        assertTrue(client.devices(com.shelfit.sentinel.core.smarthome.StructureId("x")).failureOrNull() != null)
    }

    @Test
    fun `disconnecting drops every provider, not just the current one`() = runTest {
        // Otherwise switching provider leaves a live token for an account the user has
        // stopped pointing at.
        val tuya = FakeSmartHomeClient()
        val simulated = FakeSmartHomeClient()
        val chosen = MutableStateFlow(SmartHomeProvider.TUYA)

        val client = router(
            chosen,
            mapOf(SmartHomeProvider.TUYA to tuya, SmartHomeProvider.SIMULATED to simulated),
            backgroundScope,
        )
        runCurrent()

        client.disconnect()

        assertEquals(SmartHomeState.NotConnected, tuya.state.value)
        assertEquals(SmartHomeState.NotConnected, simulated.state.value)
    }

    @Test
    fun `the feature is offered when any provider could serve`() = runTest {
        val chosen = MutableStateFlow(SmartHomeProvider.NONE)
        val client = router(
            chosen,
            mapOf(SmartHomeProvider.TUYA to FakeSmartHomeClient(available = true)),
            backgroundScope,
        )
        runCurrent()

        // Reporting the current provider's answer would hide the chooser that lets the user
        // pick a working one.
        assertTrue(client.available)
    }
}
