package com.shelfit.sentinel.ui.smarthome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.smarthome.DeviceKind
import com.shelfit.sentinel.core.smarthome.SmartHomeDevice
import com.shelfit.sentinel.core.smarthome.SmartHomeDeviceList
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
import com.shelfit.sentinel.core.smarthome.describe
import com.shelfit.sentinel.platform.smarthome.SimulatedSmartHomeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One device as the list shows it. */
data class DeviceRowUi(
    val id: String,
    val name: String,
    /** Room and kind on one line, e.g. "Living room · Light". */
    val detail: String,
    /** "On", "Off", or an explanation of why the state is not known. */
    val statusLabel: String,
    val selectable: Boolean,
    val reachable: Boolean,
    /** False when this device cannot be toggled, only switched on or off. */
    val stateKnown: Boolean,
)

/** What the screen needs to explain the connection and what to do about it. */
data class SmartHomeUiState(
    val providerAvailable: Boolean = false,
    val connectionLabel: String = "",
    val connectionDetail: String = "",
    val canConnect: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val structures: List<SmartHomeStructure> = emptyList(),
    val selectedStructure: StructureId? = null,
    val devices: List<DeviceRowUi> = emptyList(),
    val loadingDevices: Boolean = false,
    val deviceProblem: String? = null,
    val simulatorEnabled: Boolean = false,
    val simulatorFault: SimulatedSmartHomeClient.Fault = SimulatedSmartHomeClient.Fault.NONE,
)

/**
 * State for the smart-home connection screen.
 *
 * Every unhappy path is a sentence on screen rather than a silent failure: the whole point of
 * this screen is that a user whose lamp did not switch can find out why without a debugger.
 * It reads the connection from the client and the device list from the shared directory, and
 * holds no provider type of its own.
 */
class SmartHomeViewModel(private val container: AppContainer) : ViewModel() {

    private val client = container.smartHomeClient

    private val connecting = MutableStateFlow(false)

    val uiState: StateFlow<SmartHomeUiState> = combine(
        client.state,
        container.smartHomeDirectory.devices,
        container.settingsRepository.settings,
        connecting,
        container.simulatedSmartHome.fault,
    ) { connection, deviceList, settings, isConnecting, fault ->
        SmartHomeUiState(
            providerAvailable = client.available,
            connectionLabel = connection.label(),
            connectionDetail = connection.detail(),
            canConnect = connection !is SmartHomeState.NotConfigured && !isConnecting,
            connected = connection.isConnected,
            connecting = isConnecting,
            structures = (connection as? SmartHomeState.Connected)?.structures ?: emptyList(),
            selectedStructure = (connection as? SmartHomeState.Connected)?.selected,
            devices = deviceList.devices.map(::toRow),
            loadingDevices = deviceList.loading,
            deviceProblem = deviceList.problem(),
            simulatorEnabled = settings.simulatedSmartHome,
            simulatorFault = fault,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SmartHomeUiState(),
    )

    /**
     * Starts account linking.
     *
     * Must be called from a visible screen: a consent dialog needs somewhere to appear.
     */
    fun connect() {
        if (connecting.value) return
        connecting.value = true
        viewModelScope.launch {
            // The returned result is deliberately not kept: connect() also publishes the
            // outcome through the client's state, and one source of truth avoids a screen
            // showing a stale error beside a live connection.
            client.connect()
            connecting.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch { client.disconnect() }
    }

    /** Re-reads authorisation, because consent can be withdrawn while the app is away. */
    fun refresh() {
        viewModelScope.launch {
            client.refresh()
            container.smartHomeDirectory.reload()
        }
    }

    fun selectStructure(structureId: StructureId) {
        viewModelScope.launch { client.selectStructure(structureId) }
    }

    fun reloadDevices() = container.smartHomeDirectory.reload()

    fun setSimulatorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setSimulatedSmartHome(enabled)
        }
    }

    fun setSimulatorFault(fault: SimulatedSmartHomeClient.Fault) =
        container.simulatedSmartHome.setFault(fault)

    private fun toRow(device: SmartHomeDevice) = DeviceRowUi(
        id = device.id.value,
        name = device.name,
        detail = listOfNotNull(device.roomName, device.kind.label()).joinToString(" · "),
        statusLabel = when {
            !device.supported -> "This app cannot switch it"
            !device.reachable -> "Offline"
            device.isOn == true -> "On"
            device.isOn == false -> "Off"
            else -> "State unknown — cannot be toggled"
        },
        selectable = device.selectable,
        reachable = device.reachable,
        stateKnown = device.stateKnown,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SmartHomeViewModel(container) }
        }

        private fun DeviceKind.label(): String = when (this) {
            DeviceKind.LIGHT -> "Light"
            DeviceKind.OUTLET -> "Smart plug"
            DeviceKind.UNSUPPORTED -> "Not supported"
        }

        private fun SmartHomeState.label(): String = when (this) {
            SmartHomeState.NotConfigured -> "Not available in this build"
            SmartHomeState.NotConnected -> "Not connected"
            SmartHomeState.PermissionRequired -> "Permission needed"
            is SmartHomeState.Connected -> "Connected"
            is SmartHomeState.Unavailable -> "Unavailable"
        }

        private fun SmartHomeState.detail(): String = when (this) {
            SmartHomeState.NotConfigured ->
                "This build does not include the Google Home SDK, so it cannot reach your " +
                    "devices. Everything else about smart-home automations works — you can " +
                    "try the flow with the simulator below."

            SmartHomeState.NotConnected ->
                "Connect your Google account to let this app switch your lights and plugs."

            SmartHomeState.PermissionRequired ->
                "Access was withdrawn. Automations pointing at your devices will not run " +
                    "until you connect again."

            is SmartHomeState.Connected -> when (structures.size) {
                0 -> "Connected, but no homes were returned for this account."
                1 -> "Connected to ${structures.single().name}."
                else -> "Connected. Choose which home to use."
            }

            is SmartHomeState.Unavailable ->
                "Could not reach your home: ${failure.kind.describe()}." +
                    if (failure.needsUserAction) "" else " This usually clears on its own."
        }

        private fun SmartHomeDeviceList.problem(): String? = failure?.let { failure ->
            when (failure.kind) {
                SmartHomeFailure.Kind.NOT_CONNECTED -> "Connect first to see your devices."
                SmartHomeFailure.Kind.NOT_CONFIGURED ->
                    "No smart-home provider in this build."

                else -> "Could not load devices: ${failure.kind.describe()}."
            }
        }
    }
}
