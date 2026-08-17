package com.shelfit.sentinel.platform.smarthome

import com.shelfit.sentinel.core.smarthome.DeviceCommandReport
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeClient
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeDevice
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.StructureId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Routes every call to whichever provider a setting currently names.
 *
 * Exists so the simulator can be off by default and switched on from the developer screen
 * without restarting the app. A delegate rather than a flag inside each client, because
 * neither client should know that the other exists — and because everything above this,
 * the executor included, then keeps holding one stable [SmartHomeClient].
 *
 * @param simulated the pretend home. Consulted only while [useSimulated] emits true.
 * @param useSimulated the developer setting. Read per call, so flipping it takes effect on
 *   the next command rather than at the next launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectableSmartHomeClient(
    private val real: SmartHomeClient,
    private val simulated: SmartHomeClient,
    private val useSimulated: Flow<Boolean>,
    scope: CoroutineScope,
) : SmartHomeClient {

    /**
     * Whichever provider is in force, republished as the setting changes.
     *
     * `Eagerly` because the executor reads `state.value` synchronously before sending a
     * command: a lazily-started flow would still hold the initial value at that moment and
     * the automation would refuse work it could have done.
     */
    override val state: StateFlow<SmartHomeState> = useSimulated
        .distinctUntilChanged()
        .flatMapLatest { simulate -> if (simulate) simulated.state else real.state }
        .stateIn(scope, SharingStarted.Eagerly, real.state.value)

    /**
     * True if either provider could serve, since the setting can be changed at any time.
     *
     * Deliberately not the current provider's answer: the smart-home screen uses this to
     * decide whether the feature is worth showing at all, and hiding the screen would hide
     * the switch that turns the simulator on.
     */
    override val available: Boolean get() = real.available || simulated.available

    private suspend fun current(): SmartHomeClient =
        if (useSimulated.first()) simulated else real

    override suspend fun connect(): SmartHomeResult<Unit> = current().connect()

    /** Disconnects both, so a provider cannot be left linked while it is not in use. */
    override suspend fun disconnect() {
        real.disconnect()
        simulated.disconnect()
    }

    override suspend fun refresh() = current().refresh()

    override suspend fun selectStructure(structureId: StructureId) =
        current().selectStructure(structureId)

    override suspend fun devices(
        structureId: StructureId,
    ): SmartHomeResult<List<SmartHomeDevice>> = current().devices(structureId)

    override suspend fun execute(
        command: SmartHomeCommand,
        targets: List<Pair<DeviceId, String>>,
    ): DeviceCommandReport = current().execute(command, targets)
}
