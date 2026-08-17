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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.smarthome.SmartHomeProvider
import com.shelfit.sentinel.core.smarthome.SmartHomeStructure
import com.shelfit.sentinel.core.smarthome.StructureId
import com.shelfit.sentinel.platform.smarthome.SimulatedSmartHomeClient
import com.shelfit.sentinel.platform.smarthome.tuya.TuyaRegion
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
        onProvider = viewModel::setProvider,
        onSaveTuya = viewModel::saveTuyaCredentials,
        onClearTuya = viewModel::clearTuyaCredentials,
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
    onProvider: (SmartHomeProvider) -> Unit,
    onSaveTuya: (String, String, TuyaRegion) -> Unit,
    onClearTuya: () -> Unit,
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

            if (uiState.provider == SmartHomeProvider.SIMULATED) {
                FaultCard(uiState = uiState, onSimulatorFault = onSimulatorFault)
            }
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
                    Text("Connect")
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = option == provider, onClick = { onProvider(option) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = "Keys are saved for ${uiState.tuyaRegion.label}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onClearTuya, modifier = Modifier.fillMaxWidth()) {
                Text("Remove keys")
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = "Enter new keys below to replace them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Create a free Cloud project at the Tuya developer platform, link " +
                    "your Smart Life app account to it, then paste its two keys here. " +
                    "See docs/tuya-setup.md for the walkthrough.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TuyaRegion.entries.forEach { option ->
                FilterChip(
                    selected = option == region,
                    onClick = { region = option },
                    label = { Text(option.label) },
                )
            }
        }
        Text(
            text = "Must match the project's data centre in the Tuya console. The wrong one " +
                "looks exactly like a wrong key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                onSaveTuya(accessId, accessSecret, region)
                accessSecret = ""
            },
            enabled = accessId.isNotBlank() && accessSecret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save and connect")
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
            text = "Each one stays in force until you change it, so you can see what the app " +
                "does about it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            onSimulatorFault = {},
            onNavigateBack = {},
        )
    }
}
