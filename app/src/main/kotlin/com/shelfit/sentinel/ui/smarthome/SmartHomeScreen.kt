package com.shelfit.sentinel.ui.smarthome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.smarthome.SmartHomeProvider
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
import com.shelfit.sentinel.platform.smarthome.SimulatedSmartHomeClient
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaRegion
import com.shelfit.sentinel.ui.components.GhostButton
import com.shelfit.sentinel.ui.components.GradientButton
import com.shelfit.sentinel.ui.components.LinkButton
import com.shelfit.sentinel.ui.components.PillTone
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.SentinelScreen
import com.shelfit.sentinel.ui.components.ShelfDivider
import com.shelfit.sentinel.ui.components.ShelfRadio
import com.shelfit.sentinel.ui.components.StatusPill
import com.shelfit.sentinel.ui.theme.SentinelTheme
import com.shelfit.sentinel.ui.theme.Shelf

@Composable
fun SmartHomeRoute(
    container: AppContainer,
    onNavigateBack: () -> Unit,
    viewModel: SmartHomeViewModel = viewModel(
        factory = SmartHomeViewModel.factory(container),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SmartHomeScreen(
        uiState = uiState,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRefresh = viewModel::refresh,
        onSelectStructure = viewModel::selectStructure,
        onReloadDevices = viewModel::reloadDevices,
        onProvider = viewModel::setProvider,
        onSaveTuya = viewModel::saveTuyaCredentials,
        onClearTuya = viewModel::clearTuyaCredentials,
        onScanLan = viewModel::scanLocalNetwork,
        onRevealKeys = viewModel::revealLocalKeys,
        onHideKeys = viewModel::hideLocalKeys,
        onSimulatorFault = viewModel::setSimulatorFault,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Connect a smart home, choose which home to use, and see what this app can switch.
 *
 * Read-only about devices on purpose: choosing which ones an automation controls belongs to
 * the automation, not here. This screen answers "is it connected, and can it see my lamp" —
 * the two questions worth answering before a rule is blamed for not working.
 */
@Composable
fun SmartHomeScreen(
    uiState: SmartHomeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onSelectStructure: (StructureId) -> Unit,
    onReloadDevices: () -> Unit,
    onProvider: (SmartHomeProvider) -> Unit,
    onSaveTuya: (String, String, TuyaRegion) -> Unit,
    onClearTuya: () -> Unit,
    onScanLan: () -> Unit,
    onRevealKeys: () -> Unit,
    onHideKeys: () -> Unit,
    onSimulatorFault: (SimulatedSmartHomeClient.Fault) -> Unit,
    onNavigateBack: () -> Unit,
) {
    SentinelScreen(title = "Smart home", onNavigateBack = onNavigateBack) {
            ProviderCard(provider = uiState.provider, onProvider = onProvider)

            if (uiState.provider == SmartHomeProvider.TUYA) {
                TuyaCard(
                    uiState = uiState,
                    onSaveTuya = onSaveTuya,
                    onClearTuya = onClearTuya,
                )
            }

            if (uiState.provider != SmartHomeProvider.NONE) {
                ConnectionCard(
                    uiState = uiState,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onRefresh = onRefresh,
                )
            }

            if (uiState.structures.size > 1) {
                SectionCard("Home") {
                    uiState.structures.forEach { structure ->
                        StructureRow(
                            structure = structure,
                            selected = structure.id == uiState.selectedStructure,
                            onSelect = { onSelectStructure(structure.id) },
                        )
                    }
                }
            }

            if (uiState.connected) {
                DeviceCard(uiState = uiState, onReload = onReloadDevices)
            }

            if (uiState.provider == SmartHomeProvider.TUYA) {
                LanScanCard(uiState = uiState, onScanLan = onScanLan)
            }

            if (uiState.provider == SmartHomeProvider.TUYA && uiState.connected) {
                LocalKeysCard(
                    uiState = uiState,
                    onReveal = onRevealKeys,
                    onHide = onHideKeys,
                )
            }

            if (uiState.provider == SmartHomeProvider.SIMULATED) {
                FaultCard(uiState = uiState, onSimulatorFault = onSimulatorFault)
            }
        }
}

@Composable
private fun ConnectionCard(
    uiState: SmartHomeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard("Connection") {
        StatusPill(
            text = uiState.connectionLabel,
            tone = if (uiState.connected) PillTone.Good else PillTone.Neutral,
        )
        Text(
            text = uiState.connectionDetail,
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.textDim,
        )

        if (uiState.connected) {
            GhostButton(text = "Disconnect", onClick = onDisconnect)
            LinkButton(text = "Check again", onClick = onRefresh)
        } else {
            GradientButton(
                text = if (uiState.connecting) "Connecting…" else "Connect",
                onClick = onConnect,
                enabled = uiState.canConnect && !uiState.connecting,
            )
        }
    }
}

@Composable
private fun DeviceCard(uiState: SmartHomeUiState, onReload: () -> Unit) {
    SectionCard("Devices this app can switch") {
        when {
            uiState.loadingDevices -> Text(
                text = "Loading devices…",
                style = MaterialTheme.typography.bodyMedium,
            )

            uiState.deviceProblem != null -> Text(
                text = uiState.deviceProblem,
                style = MaterialTheme.typography.bodyMedium,
                color = Shelf.palette.warn,
            )

            uiState.devices.isEmpty() -> Text(
                text = "No lights or smart plugs were found in this home.",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> uiState.devices.forEach { device -> DeviceRow(device) }
        }

        LinkButton(text = "Reload", onClick = onReload)
        Text(
            text = "Only lights and smart plugs are supported for now. Other devices are " +
                "listed so you can see they were found, but this app will not try to " +
                "operate them.",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textFaint,
        )
    }
}

@Composable
private fun DeviceRow(device: DeviceRowUi) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = device.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (device.selectable) Shelf.palette.text else Shelf.palette.textDim,
        )
        Text(
            text = "${device.detail} · ${device.statusLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = if (device.selectable && device.reachable) {
                Shelf.palette.textDim
            } else {
                Shelf.palette.warn
            },
        )
    }
}

@Composable
private fun StructureRow(
    structure: SmartHomeStructure,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShelfRadio(selected = selected)
        Text(
            structure.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Shelf.palette.text,
        )
    }
}

/** Which provider an automation should talk to. */
@Composable
private fun ProviderCard(
    provider: SmartHomeProvider,
    onProvider: (SmartHomeProvider) -> Unit,
) {
    SectionCard("Smart home") {
        SmartHomeProvider.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProvider(option) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShelfRadio(selected = option == provider)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Shelf.palette.text,
                    )
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Shelf.palette.textDim,
                    )
                }
            }
        }
    }
}

/**
 * The two keys from the Tuya console, and the data centre they belong to.
 *
 * The secret is a password field and is never read back out of storage into the screen — once
 * saved, the card reports that keys exist rather than showing them. Replacing them means
 * typing both again, which is the right trade for not having a credential sitting in a
 * recomposition.
 */
@Composable
private fun TuyaCard(
    uiState: SmartHomeUiState,
    onSaveTuya: (String, String, TuyaRegion) -> Unit,
    onClearTuya: () -> Unit,
) {
    var accessId by remember { mutableStateOf("") }
    var accessSecret by remember { mutableStateOf("") }
    var region by remember(uiState.tuyaRegion) { mutableStateOf(uiState.tuyaRegion) }

    SectionCard("Tuya keys") {
        if (uiState.tuyaConfigured) {
            Text(
                text = "Keys are saved for ${uiState.tuyaRegion.label} " +
                    "(${uiState.tuyaRegion.code}).",
                style = MaterialTheme.typography.bodyMedium,
                color = Shelf.palette.text,
            )
            GhostButton(text = "Remove keys", onClick = onClearTuya)
            ShelfDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = "Enter new keys below to replace them.",
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.textDim,
            )
        } else {
            Text(
                text = "Create a free Cloud project at the Tuya developer platform, link " +
                    "your Smart Life app account to it, then paste its two keys here. " +
                    "See docs/tuya-setup.md for the walkthrough.",
                style = MaterialTheme.typography.bodyMedium,
                color = Shelf.palette.textDim,
            )
        }

        OutlinedTextField(
            value = accessId,
            onValueChange = { accessId = it },
            label = { Text("Access ID / Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = accessSecret,
            onValueChange = { accessSecret = it },
            label = { Text("Access Secret / Client Secret") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Data centre",
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.text,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TuyaRegion.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { region = option }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShelfRadio(selected = option == region)
                    // The code is shown too, so it can be matched against the Tuya console
                    // and against tinytuya, both of which name regions that way.
                    Text(
                        text = "${option.label} (${option.code})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Shelf.palette.text,
                    )
                }
            }
        }
        Text(
            text = "Must match the project's data centre in the Tuya console. The wrong one " +
                "looks exactly like a wrong key.",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textDim,
        )

        GradientButton(
            text = "Save and connect",
            onClick = {
                onSaveTuya(accessId, accessSecret, region)
                accessSecret = ""
            },
            enabled = accessId.isNotBlank() && accessSecret.isNotBlank(),
        )
    }
}

/**
 * Devices found announcing themselves on the LAN.
 *
 * Diagnostic, not control: local switching is not implemented yet, and the protocol version
 * shown here is the fact that decides how it will be. Listing a device the app cannot yet
 * drive is still useful — promising one it cannot would not be.
 */
@Composable
private fun LanScanCard(uiState: SmartHomeUiState, onScanLan: () -> Unit) {
    SectionCard("Local network") {
        Text(
            text = "Tuya devices announce themselves on your Wi-Fi. Scanning finds them " +
                "without any account, and reports which protocol each one speaks.",
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.textDim,
        )

        val found = uiState.lanDevices
        when {
            uiState.lanScanning && found.isNullOrEmpty() -> Text(
                text = "Listening…",
                style = MaterialTheme.typography.bodyMedium,
            )

            found == null -> Unit

            found.isEmpty() -> Text(
                text = "No devices answered. Check the phone is on the same Wi-Fi as the " +
                    "devices, and that the router does not have client isolation or " +
                    "\"AP isolation\" switched on.",
                style = MaterialTheme.typography.bodyMedium,
                color = Shelf.palette.warn,
            )

            else -> found.forEach { device -> LanDeviceRow(device) }
        }

        GradientButton(
            text = when {
                uiState.lanScanning -> "Listening…"
                found == null -> "Scan local network"
                else -> "Scan again"
            },
            onClick = onScanLan,
            enabled = !uiState.lanScanning,
        )

        if (!found.isNullOrEmpty()) {
            Text(
                text = "Local switching is not built yet — these are listed so you can see " +
                    "which protocol version you have. See docs/tuya-lan.md.",
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.textFaint,
            )
        }
    }
}

@Composable
private fun LanDeviceRow(device: LanDeviceRowUi) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = device.name ?: device.ip,
            style = MaterialTheme.typography.bodyLarge,
            color = Shelf.palette.text,
        )
        Text(
            text = "${device.ip} · ${device.protocolLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textDim,
        )
        SelectionContainer {
            Text(
                text = device.deviceId,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Shelf.palette.textDim,
            )
        }
    }
}

/**
 * Per-device local keys, for setting up LAN control later.
 *
 * Hidden until asked for, and not held anywhere after: these are credentials, and putting one on
 * screen every time somebody opens settings would be careless. Fetched fresh each time rather
 * than cached, so nothing is retained beyond the moment it is displayed.
 */
@Composable
private fun LocalKeysCard(
    uiState: SmartHomeUiState,
    onReveal: () -> Unit,
    onHide: () -> Unit,
) {
    SectionCard("Local keys") {
        Text(
            text = "Local control needs one secret per device, and only the cloud has it. " +
                "You need these once — write them down somewhere safe.",
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.textDim,
        )

        uiState.localKeysProblem?.let { problem ->
            Text(
                text = problem,
                style = MaterialTheme.typography.bodyMedium,
                color = Shelf.palette.warn,
            )
        }

        val keys = uiState.localKeys
        if (keys.isNullOrEmpty()) {
            GradientButton(text = "Show local keys", onClick = onReveal)
        } else {
            keys.forEach { key ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        key.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Shelf.palette.text,
                    )
                    Text(
                        text = key.reachability,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (key.directlyReachable) {
                            Shelf.palette.cyan
                        } else {
                            Shelf.palette.textDim
                        },
                    )
                    Text(
                        text = "Local key",
                        style = MaterialTheme.typography.labelSmall,
                        color = Shelf.palette.accent,
                    )
                    // Selectable so the value can be copied out rather than transcribed by eye.
                    SelectionContainer {
                        Text(
                            text = key.localKey,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Shelf.palette.text,
                        )
                    }
                    Text(
                        text = "Device ID",
                        style = MaterialTheme.typography.labelSmall,
                        color = Shelf.palette.accent,
                    )
                    SelectionContainer {
                        Text(
                            text = key.deviceId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Shelf.palette.text,
                        )
                    }
                }
                ShelfDivider()
            }
            GhostButton(text = "Hide", onClick = onHide)
            Text(
                text = "Treat these like passwords. Anyone on your Wi-Fi with a device key can " +
                    "switch that device. They change if you reset or re-pair it.",
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.warn,
            )
        }
    }
}

/**
 * Faults the simulated home can be made to produce.
 *
 * Only shown when the simulator is the chosen provider — it is a developer tool, and its
 * controls have no meaning beside a real account.
 */
@Composable
private fun FaultCard(
    uiState: SmartHomeUiState,
    onSimulatorFault: (SimulatedSmartHomeClient.Fault) -> Unit,
) {
    SectionCard("Simulate a problem") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SimulatedSmartHomeClient.Fault.entries.forEach { fault ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSimulatorFault(fault) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShelfRadio(selected = fault == uiState.simulatorFault)
                    Text(
                        text = fault.label(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Shelf.palette.text,
                    )
                }
            }
        }
        Text(
            text = "Each one stays in force until you change it, so you can see what the app " +
                "does about it.",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textFaint,
        )
    }
}

private fun SimulatedSmartHomeClient.Fault.label(): String = when (this) {
    SimulatedSmartHomeClient.Fault.NONE -> "Everything works"
    SimulatedSmartHomeClient.Fault.NETWORK_UNAVAILABLE -> "No network"
    SimulatedSmartHomeClient.Fault.PERMISSION_REVOKED -> "Permission withdrawn"
    SimulatedSmartHomeClient.Fault.HOME_UNAVAILABLE -> "Home unavailable"
    SimulatedSmartHomeClient.Fault.COMMANDS_REJECTED -> "Devices refuse commands"
}

@Preview(showBackground = true)
@Composable
private fun SmartHomeScreenPreview() {
    SentinelTheme {
        SmartHomeScreen(
            uiState = SmartHomeUiState(
                providerAvailable = true,
                connectionLabel = "Connected",
                connectionDetail = "Connected to Simulated house.",
                connected = true,
                structures = listOf(
                    SmartHomeStructure(StructureId("a"), "Simulated house"),
                    SmartHomeStructure(StructureId("b"), "Simulated flat"),
                ),
                selectedStructure = StructureId("a"),
                devices = listOf(
                    DeviceRowUi("1", "Living room lamp", "Living room · Light", "Off", true, true, true),
                    DeviceRowUi("2", "Porch light", "Outside · Light", "Offline", true, false, false),
                ),
                provider = SmartHomeProvider.SIMULATED,
            ),
            onConnect = {},
            onDisconnect = {},
            onRefresh = {},
            onSelectStructure = {},
            onReloadDevices = {},
            onProvider = {},
            onSaveTuya = { _, _, _ -> },
            onClearTuya = {},
            onScanLan = {},
            onRevealKeys = {},
            onHideKeys = {},
            onSimulatorFault = {},
            onNavigateBack = {},
        )
    }
}
