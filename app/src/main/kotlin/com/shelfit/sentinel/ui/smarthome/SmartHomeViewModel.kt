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
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.SmartHomeProvider
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
import com.shelfit.sentinel.core.smarthome.describe
import com.shelfit.sentinel.platform.smarthome.SimulatedSmartHomeClient
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaCredentials
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaLanAnnouncement
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaLocalCredential
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** A device found announcing itself on the local network. */
data class LanDeviceRowUi(
    val deviceId: String,
    val ip: String,
    val protocolLabel: String,
    /** False while local control is unimplemented — see docs/tuya-lan.md. */
    val controlSupported: Boolean,
)

/** One device's local key, for setting up LAN control later. */
data class LocalKeyRowUi(
    val name: String,
    val deviceId: String,
    val localKey: String,
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
    val provider: SmartHomeProvider = SmartHomeProvider.Default,
    /** True once keys are saved. The secret itself is never sent back to the screen. */
    val tuyaConfigured: Boolean = false,
    val tuyaRegion: TuyaRegion = TuyaRegion.Default,
    val simulatorFault: SimulatedSmartHomeClient.Fault = SimulatedSmartHomeClient.Fault.NONE,
    val lanScanning: Boolean = false,
    /** Null until a scan has been run, so "not scanned" differs from "found nothing". */
    val lanDevices: List<LanDeviceRowUi>? = null,
    /** Null until the user asks. Local keys are credentials, not something to show by default. */
    val localKeys: List<LocalKeyRowUi>? = null,
    val localKeysProblem: String? = null,
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

    private val lanScanning = MutableStateFlow(false)
    private val lanDevices = MutableStateFlow<List<LanDeviceRowUi>?>(null)
    private val localKeys = MutableStateFlow<List<LocalKeyRowUi>?>(null)
    private val localKeysProblem = MutableStateFlow<String?>(null)

    /**
     * Assembled from six sources.
     *
     * Past `combine`'s five typed overloads, so this uses the array form and casts. Ugly, and
     * still better than nesting two combines — which would recompose the screen twice for one
     * change and make the ordering hard to follow.
     */
    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SmartHomeUiState> = combine(
        client.state,
        container.smartHomeDirectory.devices,
        container.settingsRepository.settings,
        connecting,
        container.simulatedSmartHome.fault,
        lanScanning,
        lanDevices,
        localKeys,
        localKeysProblem,
    ) { values ->
        val connection = values[0] as SmartHomeState
        val deviceList = values[1] as SmartHomeDeviceList
        val settings = values[2] as com.shelfit.sentinel.data.SentinelSettings
        val isConnecting = values[3] as Boolean
        val fault = values[4] as SimulatedSmartHomeClient.Fault
        val scanning = values[5] as Boolean
        val lan = values[6] as List<LanDeviceRowUi>?
        val keys = values[7] as List<LocalKeyRowUi>?
        val keysProblem = values[8] as String?

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
            provider = settings.smartHomeProvider,
            tuyaConfigured = settings.tuya.complete,
            tuyaRegion = settings.tuya.region,
            simulatorFault = fault,
            lanScanning = scanning,
            lanDevices = lan,
            localKeys = keys,
            localKeysProblem = keysProblem,
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

    /**
     * Changes provider.
     *
     * Disconnects first: leaving the previous provider linked while a different one is in use
     * would keep a token alive for an account the user has stopped pointing at.
     */
    fun setProvider(provider: SmartHomeProvider) {
        viewModelScope.launch {
            client.disconnect()
            container.settingsRepository.setSmartHomeProvider(provider)
        }
    }

    fun saveTuyaCredentials(accessId: String, accessSecret: String, region: TuyaRegion) {
        viewModelScope.launch {
            container.settingsRepository.saveTuyaCredentials(
                TuyaCredentials(accessId = accessId, accessSecret = accessSecret, region = region),
            )
            // Connect straight away: the user has just pasted keys and wants to know whether
            // they work, not to hunt for a second button.
            client.connect()
        }
    }

    fun clearTuyaCredentials() {
        viewModelScope.launch {
            client.disconnect()
            container.settingsRepository.clearTuyaCredentials()
        }
    }

    /**
     * Listens for local announcements for a few seconds and reports what turned up.
     *
     * Time-boxed rather than continuous: devices rebroadcast every few seconds, so a short
     * window finds everything awake, and leaving three sockets open on a phone that runs for
     * weeks would be a leak for no benefit.
     *
     * Results accumulate as they arrive so a slow device still appears, and are keyed by id
     * because a device announces repeatedly and on more than one port.
     */
    fun scanLocalNetwork() {
        if (lanScanning.value) return
        lanScanning.value = true
        lanDevices.value = emptyList()

        viewModelScope.launch {
            val found = linkedMapOf<String, TuyaLanAnnouncement>()
            withTimeoutOrNull(SCAN_MILLIS) {
                container.tuyaLanDiscovery.announcements().collect { announcement ->
                    found[announcement.deviceId] = announcement
                    lanDevices.value = found.values.map(::toLanRow)
                }
            }
            lanDevices.value = found.values.map(::toLanRow)
            lanScanning.value = false
        }
    }

    private fun toLanRow(announcement: TuyaLanAnnouncement) = LanDeviceRowUi(
        deviceId = announcement.deviceId,
        ip = announcement.ip,
        protocolLabel = announcement.protocolVersion
            ?.let { "Protocol $it" }
            ?: "Protocol not reported",
        controlSupported = announcement.controlSupported,
    )

    /**
     * Fetches the per-device local keys, for setting up LAN control later.
     *
     * Only on request. These are credentials — showing them beside the device list by default
     * would put a secret on screen every time somebody opened settings.
     */
    fun revealLocalKeys() {
        localKeysProblem.value = null
        viewModelScope.launch {
            when (val result = container.tuyaCloudClient.localCredentials()) {
                is SmartHomeResult.Success -> {
                    localKeys.value = result.value
                        .filter(TuyaLocalCredential::usable)
                        .map { LocalKeyRowUi(it.name, it.deviceId, it.localKey) }
                    if (localKeys.value.isNullOrEmpty()) {
                        localKeysProblem.value =
                            "Connected, but no local keys came back. This usually means the " +
                            "app account is not linked to the cloud project."
                    }
                }

                is SmartHomeResult.Failure -> {
                    localKeys.value = null
                    localKeysProblem.value =
                        "Could not read the keys: ${result.failure.kind.describe()}."
                }
            }
        }
    }

    /** Clears the keys from the screen. They are re-fetched, never cached. */
    fun hideLocalKeys() {
        localKeys.value = null
        localKeysProblem.value = null
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

        /** Long enough for every awake device to rebroadcast at least once. */
        private const val SCAN_MILLIS = 8_000L

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
                "Choose a smart home below. Until then, automations can only do things on " +
                    "this phone."

            SmartHomeState.NotConnected ->
                "Not linked yet. Connect to let automations switch your lights and plugs."

            SmartHomeState.PermissionRequired ->
                "Access was refused. Automations pointing at your devices will not run " +
                    "until you connect again — check your keys, and that the app account is " +
                    "still linked to your cloud project."

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
