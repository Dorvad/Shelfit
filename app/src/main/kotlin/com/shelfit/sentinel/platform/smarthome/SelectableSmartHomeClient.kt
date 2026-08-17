package com.shelfit.sentinel.platform.smarthome

import com.shelfit.sentinel.core.smarthome.DeviceCommandReport
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeClient
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeDevice
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeProvider
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Routes every call to whichever provider the user has chosen.
 *
 * A delegate rather than a flag inside each client, because no provider should know that the
 * others exist — and because everything above this, the executor included, then holds one
 * stable [SmartHomeClient] regardless of what the setting says.
 *
 * [SmartHomeProvider.NONE] is served by a nothing-configured client rather than by a null,
 * so no caller needs a special case for "no smart home".
 *
 * @param clients one entry per provider. Missing entries fall back to the unconfigured client,
 *   which is what makes a provider that this build cannot supply a quiet absence rather than
 *   a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectableSmartHomeClient(
    private val clients: Map<SmartHomeProvider, SmartHomeClient>,
    private val chosen: Flow<SmartHomeProvider>,
    scope: CoroutineScope,
) : SmartHomeClient {

    /** Stands in for a provider this build does not offer, and for "none". */
    private val unconfigured = object : SmartHomeClient {
        private val notConfigured =
            SmartHomeFailure(SmartHomeFailure.Kind.NOT_CONFIGURED, detail = "No smart home chosen")

        override val state: StateFlow<SmartHomeState> =
            stateIn(flowOf(SmartHomeState.NotConfigured))
        override val available: Boolean = false
        override suspend fun connect() = SmartHomeResult.Failure(notConfigured)
        override suspend fun disconnect() = Unit
        override suspend fun refresh() = Unit
        override suspend fun selectStructure(structureId: StructureId) = Unit
        override suspend fun devices(structureId: StructureId) =
            SmartHomeResult.Failure(notConfigured)

        override suspend fun execute(
            command: SmartHomeCommand,
            targets: List<Pair<DeviceId, String>>,
        ) = DeviceCommandReport.allFailing(targets, notConfigured)

        private fun stateIn(flow: Flow<SmartHomeState>) =
            flow.stateIn(scope, SharingStarted.Eagerly, SmartHomeState.NotConfigured)
    }

    /**
     * The chosen provider's state, republished as the choice changes.
     *
     * `Eagerly` because the executor reads `state.value` synchronously before sending a
     * command: a lazily-started flow would still hold its initial value at that moment, and
     * the automation would refuse work it could have done.
     */
    override val state: StateFlow<SmartHomeState> = chosen
        .distinctUntilChanged()
        .flatMapLatest { provider -> clientFor(provider).state }
        .stateIn(scope, SharingStarted.Eagerly, SmartHomeState.NotConfigured)

    /**
     * True if any provider could serve, since the choice can change at any time.
     *
     * Deliberately not the current provider's answer: the smart-home screen uses this to
     * decide whether the feature is worth showing, and hiding it would hide the chooser.
     */
    override val available: Boolean get() = clients.values.any { it.available }

    private fun clientFor(provider: SmartHomeProvider): SmartHomeClient =
        clients[provider] ?: unconfigured

    private suspend fun current(): SmartHomeClient = clientFor(chosen.first())

    override suspend fun connect(): SmartHomeResult<Unit> = current().connect()

    /** Disconnects every provider, so none is left linked while it is not in use. */
    override suspend fun disconnect() {
        clients.values.forEach { it.disconnect() }
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
