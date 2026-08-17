package com.shelfit.sentinel.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.sensor.SensorAvailability
import com.shelfit.sentinel.core.sensor.SensorKind
import com.shelfit.sentinel.core.sensor.SensorStatus
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.core.trigger.TriggerState
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme

@Composable
fun DashboardRoute(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenClapLab: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenRules: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permission = rememberMicrophonePermissionState()

    // Permissions can be changed in system Settings while the app is backgrounded.
    LifecycleResumeEffect(permission.granted) {
        viewModel.refreshSensorStatus()
        onPauseOrDispose { }
    }

    DashboardScreen(
        uiState = uiState,
        permission = permission,
        onToggleSensorMode = viewModel::toggleSensorMode,
        onResume = viewModel::resumeListening,
        onOpenSettings = onOpenSettings,
        onOpenClapLab = onOpenClapLab,
        onOpenHealth = onOpenHealth,
        onOpenRules = onOpenRules,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    permission: MicrophonePermissionState,
    onToggleSensorMode: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenClapLab: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenRules: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shelfit Sentinel") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tune),
                            contentDescription = "Settings",
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!permission.granted) {
                MicrophonePermissionCard(permission)
            }

            SensorStatusCard(uiState.sensors)

            uiState.triggers.forEach { trigger ->
                TriggerCard(trigger)
            }

            SensorModeControl(
                uiState = uiState,
                enabled = permission.granted,
                onToggleSensorMode = onToggleSensorMode,
                onResume = onResume,
            )

            TextButton(
                onClick = onOpenRules,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Automations") }

            TextButton(
                onClick = onOpenHealth,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sensor health and setup") }

            TextButton(
                onClick = onOpenClapLab,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open clap detector test") }
        }
    }
}

/**
 * Asks for the microphone at the point the user can see why it is needed, rather
 * than on first launch.
 */
@Composable
private fun MicrophonePermissionCard(permission: MicrophonePermissionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Microphone access is required to hear claps",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "Audio is analysed on this device and never recorded or sent " +
                    "anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (permission.deniedAfterRequest) {
                Button(onClick = permission.openAppSettings) { Text("Open app settings") }
            } else {
                Button(onClick = permission.request) { Text("Grant microphone access") }
            }
        }
    }
}

@Composable
private fun SensorStatusCard(sensors: List<SensorStatus>) {
    SectionCard(title = "Sensor status") {
        if (sensors.isEmpty()) {
            Text("No sensors required", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        sensors.forEach { status ->
            LabelledRow(
                label = status.kind.label(),
                value = status.availability.label(),
                emphasise = status.availability != SensorAvailability.AVAILABLE,
            )
        }
    }
}

@Composable
private fun TriggerCard(trigger: TriggerRowUi) {
    SectionCard(title = "Trigger") {
        Text(trigger.name, style = MaterialTheme.typography.titleMedium)
        Text(
            trigger.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LabelledRow(
            label = "Detector",
            value = trigger.state.label(),
            emphasise = trigger.state !is TriggerState.Active,
        )
        if (trigger.actionNames.isEmpty()) {
            LabelledRow(
                label = "Action",
                value = if (trigger.rulesWithoutAction > 0) "No action set" else "No automations",
                emphasise = true,
            )
        } else {
            trigger.actionNames.forEachIndexed { index, action ->
                LabelledRow(
                    label = if (index == 0) "Action" else " ",
                    value = action,
                )
            }
        }
    }
}

@Composable
private fun SensorModeControl(
    uiState: DashboardUiState,
    enabled: Boolean,
    onToggleSensorMode: () -> Unit,
    onResume: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.health.resumeRequired) {
            Button(
                onClick = onResume,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Resume listening") }
        } else if (uiState.sensorModeEnabled) {
            OutlinedButton(onClick = onToggleSensorMode, modifier = Modifier.fillMaxWidth()) {
                Text("Turn off Sensor Mode")
            }
        } else {
            Button(
                onClick = onToggleSensorMode,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Turn on Sensor Mode") }
        }
        Text(
            text = if (uiState.isRunning) {
                "Listening with the screen off. Keep the phone plugged in."
            } else {
                "Sensor Mode runs a foreground service so listening continues with the " +
                    "screen off."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun LabelledRow(label: String, value: String, emphasise: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasise) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun SensorKind.label(): String = when (this) {
    SensorKind.MICROPHONE -> "Microphone"
    SensorKind.CAMERA -> "Camera"
    SensorKind.AMBIENT_LIGHT -> "Ambient light"
    SensorKind.ACCELEROMETER -> "Accelerometer"
    SensorKind.PROXIMITY -> "Proximity"
}

private fun SensorAvailability.label(): String = when (this) {
    SensorAvailability.AVAILABLE -> "Ready"
    SensorAvailability.PERMISSION_REQUIRED -> "Permission required"
    SensorAvailability.UNSUPPORTED -> "Not available on this device"
}

private fun TriggerState.label(): String = when (this) {
    TriggerState.Idle -> "Stopped"
    TriggerState.Starting -> "Starting"
    TriggerState.Active -> "Listening"
    is TriggerState.Failed -> "Failed: $message"
    is TriggerState.Unavailable -> when (reason) {
        TriggerState.Reason.MISSING_PERMISSION -> "Permission required"
        TriggerState.Reason.MISSING_SENSOR -> "Sensor unavailable"
        TriggerState.Reason.NOT_IMPLEMENTED -> message ?: "Not implemented yet"
        TriggerState.Reason.DISABLED -> "Disabled"
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardScreenPreview() {
    SentinelTheme {
        DashboardScreen(
            uiState = DashboardUiState(
                isRunning = false,
                sensors = listOf(
                    SensorStatus(SensorKind.MICROPHONE, SensorAvailability.PERMISSION_REQUIRED),
                ),
                triggers = listOf(
                    TriggerRowUi(
                        id = TriggerId.DoubleClap,
                        name = "Double clap",
                        description = "Clap twice, quickly",
                        state = TriggerState.Idle,
                        actionNames = listOf("Vibrate the phone"),
                        rulesWithoutAction = 0,
                    ),
                ),
            ),
            permission = MicrophonePermissionState(
                granted = false,
                deniedAfterRequest = false,
                request = {},
                openAppSettings = {},
            ),
            onToggleSensorMode = {},
            onResume = {},
            onOpenSettings = {},
            onOpenClapLab = {},
            onOpenHealth = {},
            onOpenRules = {},
        )
    }
}
