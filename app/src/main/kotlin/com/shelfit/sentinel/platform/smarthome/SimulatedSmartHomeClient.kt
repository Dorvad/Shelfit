package com.shelfit.sentinel.platform.smarthome

import com.shelfit.sentinel.core.smarthome.DeviceCommandReport
import com.shelfit.sentinel.core.smarthome.DeviceCommandResult
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.DeviceKind
import com.shelfit.sentinel.core.smarthome.SmartHomeClient
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeDevice
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A pretend smart home, for developing and demonstrating the feature without a Google
 * account or any hardware.
 *
 * **Not a Google Home integration and never presented as one.** It exists because the real
 * client cannot be written yet (see [GoogleHomeClient]) and because the interesting parts of
 * this feature are the failures: a lamp that is offline, a device removed since the rule was
 * saved, a toggle on something that does not report its state, consent withdrawn between
 * saving a rule and running it. Those are hard to stage with real hardware and trivial to
 * stage here, so the error handling the user asked for is exercisable rather than asserted.
 *
 * It is off by default and reachable only from the developer settings, so no user is ever
 * shown a fake home they might mistake for their own.
 *
 * The device table is deliberately awkward. Each entry covers a case the UI has to get
 * right, including one device that reports no state — which is the only thing that makes
 * [SmartHomeCommand.TOGGLE] impossible, and therefore the only way to see the rule editor
 * refuse it.
 */
class SimulatedSmartHomeClient : SmartHomeClient {

    /**
     * A condition to simulate on the next call.
     *
     * Set from the developer screen. Sticky rather than one-shot: reproducing what a user
     * sees when their network is down means having it stay down while you look at the app.
     */
    enum class Fault {
        NONE,
        NETWORK_UNAVAILABLE,
        PERMISSION_REVOKED,
        HOME_UNAVAILABLE,
        COMMANDS_REJECTED,
    }

    private val _state = MutableStateFlow<SmartHomeState>(SmartHomeState.NotConnected)
    override val state: StateFlow<SmartHomeState> = _state.asStateFlow()

    override val available: Boolean = true

    private val _fault = MutableStateFlow(Fault.NONE)
    val fault: StateFlow<Fault> = _fault.asStateFlow()

    /**
     * Guards the device table.
     *
     * Not paranoia: a rule fires on the detection coroutine while the device screen reads
     * on the main one, so a real toggle and a real list happen concurrently.
     */
    private val mutex = Mutex()

    private val devices: MutableMap<DeviceId, SmartHomeDevice> =
        seedDevices().associateBy { it.id }.toMutableMap()

    fun setFault(fault: Fault) {
        _fault.value = fault
        // A withdrawn permission has to show in the state, not just at the next call —
        // that is the whole point of the UI observing it.
        _state.value = when {
            fault == Fault.PERMISSION_REVOKED -> SmartHomeState.PermissionRequired
            _state.value == SmartHomeState.PermissionRequired -> SmartHomeState.NotConnected
            else -> _state.value
        }
    }

    override suspend fun connect(): SmartHomeResult<Unit> {
        currentFailure()?.let {
            _state.value = SmartHomeState.Unavailable(it)
            return SmartHomeResult.Failure(it)
        }
        _state.value = SmartHomeState.Connected(structures = structures)
        return SmartHomeResult.Success(Unit)
    }

    override suspend fun disconnect() {
        _state.value = SmartHomeState.NotConnected
    }

    override suspend fun refresh() {
        val failure = currentFailure()
        _state.value = when {
            failure != null -> SmartHomeState.Unavailable(failure)
            _state.value.isConnected -> _state.value
            else -> SmartHomeState.NotConnected
        }
    }

    override suspend fun selectStructure(structureId: StructureId) {
        val connected = _state.value as? SmartHomeState.Connected ?: return
        if (structures.none { it.id == structureId }) return
        _state.value = connected.copy(selected = structureId)
    }

    override suspend fun devices(
        structureId: StructureId,
    ): SmartHomeResult<List<SmartHomeDevice>> {
        currentFailure()?.let { return SmartHomeResult.Failure(it) }
        if (!_state.value.isConnected) {
            return SmartHomeResult.Failure(SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONNECTED))
        }
        val known = structureDevices[structureId]
            ?: return SmartHomeResult.Failure(
                SmartHomeFailure(SmartHomeFailure.Kind.HOME_UNAVAILABLE),
            )
        return mutex.withLock {
            SmartHomeResult.Success(known.mapNotNull { devices[it] })
        }
    }

    override suspend fun execute(
        command: SmartHomeCommand,
        targets: List<Pair<DeviceId, String>>,
    ): DeviceCommandReport {
        currentFailure()?.let { return DeviceCommandReport.allFailing(targets, it) }
        if (!_state.value.isConnected) {
            return DeviceCommandReport.allFailing(
                targets,
                SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONNECTED),
            )
        }

        return mutex.withLock {
            DeviceCommandReport(
                targets.map { (id, name) -> switch(command, id, name) },
            )
        }
    }

    /**
     * One device, with every reason it might not work checked in the order a real provider
     * would hit them: does it exist, can this app operate it, is it responding, and only
     * then can the command be carried out.
     */
    private fun switch(
        command: SmartHomeCommand,
        id: DeviceId,
        rememberedName: String,
    ): DeviceCommandResult {
        val device = devices[id]
            ?: return failed(id, rememberedName, SmartHomeFailure.Kind.DEVICE_REMOVED)

        val name = device.name
        if (!device.supported) {
            return failed(id, name, SmartHomeFailure.Kind.DEVICE_UNSUPPORTED)
        }
        if (!device.reachable) {
            return failed(id, name, SmartHomeFailure.Kind.DEVICE_OFFLINE)
        }
        if (_fault.value == Fault.COMMANDS_REJECTED) {
            return failed(id, name, SmartHomeFailure.Kind.COMMAND_REJECTED)
        }

        val target = when (command) {
            SmartHomeCommand.ON -> true
            SmartHomeCommand.OFF -> false
            // Declining beats guessing: a toggle that picks a direction at random is worse
            // than one that says it cannot tell.
            SmartHomeCommand.TOGGLE -> device.isOn?.not()
                ?: return failed(id, name, SmartHomeFailure.Kind.DEVICE_STATE_UNKNOWN)
        }

        devices[id] = device.copy(isOn = target)
        return DeviceCommandResult(id, name, failure = null)
    }

    private fun failed(
        id: DeviceId,
        name: String,
        kind: SmartHomeFailure.Kind,
    ) = DeviceCommandResult(id, name, SmartHomeFailure(kind, deviceName = name))

    private fun currentFailure(): SmartHomeFailure? = when (_fault.value) {
        Fault.NONE, Fault.COMMANDS_REJECTED -> null
        Fault.NETWORK_UNAVAILABLE -> SmartHomeFailure(SmartHomeFailure.Kind.NETWORK_UNAVAILABLE)
        Fault.PERMISSION_REVOKED -> SmartHomeFailure(SmartHomeFailure.Kind.PERMISSION_DENIED)
        Fault.HOME_UNAVAILABLE -> SmartHomeFailure(SmartHomeFailure.Kind.HOME_UNAVAILABLE)
    }

    private companion object {

        val Home = StructureId("sim.home")
        val Flat = StructureId("sim.flat")

        val structures = listOf(
            SmartHomeStructure(Home, "Simulated house"),
            SmartHomeStructure(Flat, "Simulated flat"),
        )

        /** Two homes, so the structure picker has something to pick between. */
        val structureDevices: Map<StructureId, List<DeviceId>> = mapOf(
            Home to listOf(
                DeviceId("sim.lamp.living"),
                DeviceId("sim.lamp.hall"),
                DeviceId("sim.plug.kettle"),
                DeviceId("sim.plug.stateless"),
                DeviceId("sim.lamp.offline"),
                DeviceId("sim.thermostat"),
            ),
            Flat to listOf(DeviceId("sim.lamp.bedside")),
        )

        fun seedDevices(): List<SmartHomeDevice> = listOf(
            SmartHomeDevice(
                id = DeviceId("sim.lamp.living"),
                name = "Living room lamp",
                roomName = "Living room",
                kind = DeviceKind.LIGHT,
                reachable = true,
                isOn = false,
            ),
            SmartHomeDevice(
                id = DeviceId("sim.lamp.hall"),
                name = "Hall light",
                roomName = "Hall",
                kind = DeviceKind.LIGHT,
                reachable = true,
                isOn = true,
            ),
            SmartHomeDevice(
                id = DeviceId("sim.plug.kettle"),
                name = "Kettle plug",
                roomName = "Kitchen",
                kind = DeviceKind.OUTLET,
                reachable = true,
                isOn = false,
            ),
            // Reports no state, so Toggle is impossible for it and On/Off are not.
            SmartHomeDevice(
                id = DeviceId("sim.plug.stateless"),
                name = "Old outlet",
                roomName = "Garage",
                kind = DeviceKind.OUTLET,
                reachable = true,
                isOn = null,
            ),
            SmartHomeDevice(
                id = DeviceId("sim.lamp.offline"),
                name = "Porch light",
                roomName = "Outside",
                kind = DeviceKind.LIGHT,
                reachable = false,
                isOn = null,
            ),
            // Listed but not selectable: this app will not try to operate a thermostat.
            SmartHomeDevice(
                id = DeviceId("sim.thermostat"),
                name = "Hallway thermostat",
                roomName = "Hall",
                kind = DeviceKind.UNSUPPORTED,
                reachable = true,
                isOn = null,
            ),
            SmartHomeDevice(
                id = DeviceId("sim.lamp.bedside"),
                name = "Bedside lamp",
                roomName = "Bedroom",
                kind = DeviceKind.LIGHT,
                reachable = true,
                isOn = false,
            ),
        )
    }
}
