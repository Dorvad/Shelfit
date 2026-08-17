package com.shelfit.sentinel.core.smarthome

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The devices in the chosen home, and how that attempt went. */
data class SmartHomeDeviceList(
    val structureId: StructureId? = null,
    val devices: List<SmartHomeDevice> = emptyList(),
    val loading: Boolean = false,
    val failure: SmartHomeFailure? = null,
) {
    /** What a rule may point at: a light or an outlet, offline or not. */
    val selectable: List<SmartHomeDevice> get() = devices.filter { it.selectable }

    val loaded: Boolean get() = !loading && failure == null && structureId != null
}

/**
 * Holds the chosen home's device list, reloading it when the choice changes.
 *
 * One instance for the whole app rather than one per screen. The connect screen and the rule
 * editor both need the same list, and a user who has just seen their lamp appear on one
 * screen should not wait for a second round trip to pick it on the other. It also means a
 * withdrawn permission empties one list, not two that can disagree.
 *
 * Plain Kotlin: it holds a [SmartHomeClient], and the vendor SDK is behind that.
 */
class SmartHomeDirectory(
    private val client: SmartHomeClient,
    private val scope: CoroutineScope,
) {

    private val _devices = MutableStateFlow(SmartHomeDeviceList())
    val devices: StateFlow<SmartHomeDeviceList> = _devices.asStateFlow()

    /** Cancelled and replaced on every reload, so a slow response cannot land after a newer one. */
    private var loadJob: Job? = null

    init {
        scope.launch {
            client.state
                .map { (it as? SmartHomeState.Connected)?.selected }
                .distinctUntilChanged()
                .collect { structureId ->
                    if (structureId == null) {
                        // Disconnected, or consent withdrawn. Clearing the list matters:
                        // leaving devices on screen after losing access invites taps that
                        // cannot work.
                        loadJob?.cancel()
                        _devices.value = SmartHomeDeviceList()
                    } else {
                        load(structureId)
                    }
                }
        }
    }

    /** Re-reads the current home. Cheap enough for a pull-to-refresh or a resumed screen. */
    fun reload() {
        val structureId = (client.state.value as? SmartHomeState.Connected)?.selected ?: return
        load(structureId)
    }

    private fun load(structureId: StructureId) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _devices.value = SmartHomeDeviceList(structureId = structureId, loading = true)
            _devices.value = when (val result = client.devices(structureId)) {
                is SmartHomeResult.Success ->
                    SmartHomeDeviceList(structureId = structureId, devices = result.value)

                is SmartHomeResult.Failure ->
                    SmartHomeDeviceList(structureId = structureId, failure = result.failure)
            }
        }
    }
}
