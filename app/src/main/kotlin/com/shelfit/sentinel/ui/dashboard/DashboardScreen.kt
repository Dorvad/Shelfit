package com.shelfit.sentinel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.shelfit.sentinel.ui.components.BreathingDot
import com.shelfit.sentinel.ui.components.GhostButton
import com.shelfit.sentinel.ui.components.GlassCard
import com.shelfit.sentinel.ui.components.GradientButton
import com.shelfit.sentinel.ui.components.HeroCard
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.SectionLabel
import com.shelfit.sentinel.ui.components.ShelfDivider
import com.shelfit.sentinel.ui.components.shelfBackground
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme
import com.shelfit.sentinel.ui.theme.Shelf

@Composable
fun DashboardRoute(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenClapLab: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenShelf: () -> Unit,
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
        onOpenShelf = onOpenShelf,
    )
}

/**
 * The home screen, laid out as the hi-fi artboard draws it: the sensor-mode state as the
 * screen's one hero object, then status, then the trigger, then a quiet list of doors to
 * everywhere else.
 *
 * The hero also holds the way onto the shelf face when listening is running — the moment
 * the phone is doing its job is exactly the moment you want to set it down and let it
 * become a decor object.
 */
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
    onOpenShelf: () -> Unit,
) {
    val palette = Shelf.palette
    Box(
        Modifier
            .fillMaxSize()
            .shelfBackground(palette.gradientTop, palette.gradientMid, palette.gradientBottom),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The artboard's header: app name left, a ringed settings glyph right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Shelfit Sentinel",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .border(1.dp, palette.cardLine, CircleShape)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tune),
                        contentDescription = "Settings",
                        tint = Color(0xFFBDD2F6),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                if (!permission.granted) {
                    MicrophonePermissionCard(permission)
                }

                SensorModeHero(
                    uiState = uiState,
                    enabled = permission.granted,
                    onToggleSensorMode = onToggleSensorMode,
                    onResume = onResume,
                    onOpenShelf = onOpenShelf,
                )

                SensorStatusCard(uiState.sensors)

                uiState.triggers.forEach { trigger ->
                    TriggerCard(trigger)
                }

                NavigationCard(
                    onOpenRules = onOpenRules,
                    onOpenHealth = onOpenHealth,
                    onOpenClapLab = onOpenClapLab,
                )
            }
        }
    }
}

/**
 * The screen's bright object: where Sensor Mode stands and the one control that changes it.
 *
 * State first, control second, and while listening a second door: the shelf face. That
 * button is the product's happy path — turn it on, put it on the shelf — so it lives in
 * the hero, not buried in a menu.
 */
@Composable
private fun SensorModeHero(
    uiState: DashboardUiState,
    enabled: Boolean,
    onToggleSensorMode: () -> Unit,
    onResume: () -> Unit,
    onOpenShelf: () -> Unit,
) {
    val palette = Shelf.palette
    HeroCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (uiState.isRunning) {
                BreathingDot(size = 9.dp)
            } else {
                Box(
                    Modifier
                        .padding(9.dp)
                        .size(9.dp)
                        .background(Color(0x668CA3C9), CircleShape),
                )
            }
            Text(
                text = when {
                    uiState.health.resumeRequired -> "Listening is paused"
                    uiState.isRunning -> "Sensor Mode is on"
                    else -> "Sensor Mode is off"
                },
                style = MaterialTheme.typography.titleMedium,
                color = palette.text,
            )
        }
        Text(
            text = when {
                uiState.health.resumeRequired ->
                    "Android needs one tap after a reboot or update."
                uiState.isRunning ->
                    "Listening with the screen off. Keep the phone plugged in."
                else ->
                    "Sensor Mode runs a foreground service so listening continues " +
                        "with the screen off."
            },
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = palette.textDim,
        )
        when {
            uiState.health.resumeRequired -> GradientButton(
                text = "Resume listening",
                onClick = onResume,
                enabled = enabled,
            )

            uiState.sensorModeEnabled -> {
                GhostButton(text = "Turn off Sensor Mode", onClick = onToggleSensorMode)
                if (uiState.isRunning) {
                    GradientButton(text = "Shelf display", onClick = onOpenShelf)
                }
            }

            else -> GradientButton(
                text = "Turn on Sensor Mode",
                onClick = onToggleSensorMode,
                enabled = enabled,
            )
        }
    }
}

/**
 * Asks for the microphone at the point the user can see why it is needed, rather
 * than on first launch.
 */
@Composable
private fun MicrophonePermissionCard(permission: MicrophonePermissionState) {
    val palette = Shelf.palette
    GlassCard {
        Text(
            text = "Microphone access is required to hear claps",
            style = MaterialTheme.typography.titleMedium,
            color = palette.warn,
        )
        Text(
            text = "Audio is analysed on this device and never recorded or sent anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textDim,
        )
        if (permission.deniedAfterRequest) {
            GradientButton(text = "Open app settings", onClick = permission.openAppSettings)
        } else {
            GradientButton(text = "Grant microphone access", onClick = permission.request)
        }
    }
}

@Composable
private fun SensorStatusCard(sensors: List<SensorStatus>) {
    SectionCard(title = "Sensor status") {
        if (sensors.isEmpty()) {
            Text(
                "No sensors required",
                style = MaterialTheme.typography.bodyMedium,
                color = Shelf.palette.textDim,
            )
            return@SectionCard
        }
        sensors.forEach { status ->
            StatusRow(
                label = status.kind.label(),
                value = status.availability.label(),
                good = status.availability == SensorAvailability.AVAILABLE,
            )
        }
    }
}

/** Key–value row where a good value glows cyan, the design's status colour. */
@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
    val palette = Shelf.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.text)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (good) palette.cyan else palette.warn,
        )
    }
}

@Composable
private fun TriggerCard(trigger: TriggerRowUi) {
    val palette = Shelf.palette
    GlassCard {
        SectionLabel("Trigger")
        Text(
            trigger.name,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = palette.text,
        )
        Text(
            trigger.description,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textDim,
        )
        ShelfDivider(Modifier.padding(vertical = 4.dp))
        StatusRow(
            label = "Detector",
            value = trigger.state.label(),
            good = trigger.state is TriggerState.Active,
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

/** The artboard's door list: three rows, chevrons, hairlines between. */
@Composable
private fun NavigationCard(
    onOpenRules: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenClapLab: () -> Unit,
) {
    GlassCard(contentPadding = 0.dp) {
        NavRow("Automations", onOpenRules, showDivider = true)
        NavRow("Sensor health and setup", onOpenHealth, showDivider = true)
        NavRow("Clap detector test", onOpenClapLab, showDivider = false)
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Shelf.palette.text,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = Color(0xFF5E769F),
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            ShelfDivider(Modifier.padding(horizontal = 16.dp))
        }
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
                isRunning = true,
                sensors = listOf(
                    SensorStatus(SensorKind.MICROPHONE, SensorAvailability.AVAILABLE),
                ),
                triggers = listOf(
                    TriggerRowUi(
                        id = TriggerId.DoubleClap,
                        name = "Double clap",
                        description = "Clap twice, quickly",
                        state = TriggerState.Active,
                        actionNames = listOf("Toggle Living room lamp"),
                        rulesWithoutAction = 0,
                    ),
                ),
            ),
            permission = MicrophonePermissionState(
                granted = true,
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
            onOpenShelf = {},
        )
    }
}
