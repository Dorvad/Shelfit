package com.shelfit.sentinel.ui.smarthome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
import com.shelfit.sentinel.platform.smarthome.SimulatedSmartHomeClient
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.theme.SentinelTheme

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
        onSimulatorEnabled = viewModel::setSimulatorEnabled,
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartHomeScreen(
    uiState: SmartHomeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onSelectStructure: (StructureId) -> Unit,
    onReloadDevices: () -> Unit,
    onSimulatorEnabled: (Boolean) -> Unit,
    onSimulatorFault: (SimulatedSmartHomeClient.Fault) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart home") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(
                uiState = uiState,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onRefresh = onRefresh,
            )

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

            SimulatorCard(
                uiState = uiState,
                onSimulatorEnabled = onSimulatorEnabled,
                onSimulatorFault = onSimulatorFault,
            )
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
        Text(
            text = uiState.connectionLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = uiState.connectionDetail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.connected) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Check again")
            }
        } else {
            Button(
                onClick = onConnect,
                enabled = uiState.canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text("Connect Google Home")
                }
            }
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
                color = MaterialTheme.colorScheme.error,
            )

            uiState.devices.isEmpty() -> Text(
                text = "No lights or smart plugs were found in this home.",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> uiState.devices.forEach { device -> DeviceRow(device) }
        }

        TextButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
            Text("Reload")
        }
        Text(
            text = "Only lights and smart plugs are supported for now. Other devices are " +
                "listed so you can see they were found, but this app will not try to " +
                "operate them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(device: DeviceRowUi) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = device.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (device.selectable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = "${device.detail} · ${device.statusLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = if (device.selectable && device.reachable) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(structure.name, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The pretend home, and the faults it can be made to produce.
 *
 * Kept on this screen rather than hidden in developer settings because it is only useful
 * where its effect is visible, and labelled unambiguously so nobody mistakes a simulated
 * lamp for their own.
 */
@Composable
private fun SimulatorCard(
    uiState: SmartHomeUiState,
    onSimulatorEnabled: (Boolean) -> Unit,
    onSimulatorFault: (SimulatedSmartHomeClient.Fault) -> Unit,
) {
    SectionCard("Developer — simulated home") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use a simulated home", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Pretend devices, for trying automations without a Google " +
                        "account. Nothing here touches real hardware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = uiState.simulatorEnabled, onCheckedChange = onSimulatorEnabled)
        }

        if (uiState.simulatorEnabled) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Simulate a problem", style = MaterialTheme.typography.bodyLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SimulatedSmartHomeClient.Fault.entries.forEach { fault ->
                    FilterChip(
                        selected = fault == uiState.simulatorFault,
                        onClick = { onSimulatorFault(fault) },
                        label = { Text(fault.label()) },
                    )
                }
            }
            Text(
                text = "Each one stays in force until you change it, so you can see what " +
                    "the app does about it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                simulatorEnabled = true,
            ),
            onConnect = {},
            onDisconnect = {},
            onRefresh = {},
            onSelectStructure = {},
            onReloadDevices = {},
            onSimulatorEnabled = {},
            onSimulatorFault = {},
            onNavigateBack = {},
        )
    }
}
