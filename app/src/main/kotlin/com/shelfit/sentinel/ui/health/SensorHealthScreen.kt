package com.shelfit.sentinel.ui.health

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.sensormode.BatteryOptimisationStatus
import com.shelfit.sentinel.core.sensormode.ListeningMode
import com.shelfit.sentinel.core.sensormode.PermissionStatus
import com.shelfit.sentinel.core.sensormode.SensorHealth
import com.shelfit.sentinel.core.sensormode.SensorModeError
import com.shelfit.sentinel.ui.components.GhostButton
import com.shelfit.sentinel.ui.components.GlassCard
import com.shelfit.sentinel.ui.components.GradientButton
import com.shelfit.sentinel.ui.components.HeroCard
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.LinkButton
import com.shelfit.sentinel.ui.components.SentinelScreen
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.ShelfDivider
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.NotificationPermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberNotificationPermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme
import com.shelfit.sentinel.ui.theme.Shelf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SensorHealthRoute(
    container: AppContainer,
    onNavigateBack: () -> Unit,
    viewModel: SensorHealthViewModel = viewModel(
        factory = SensorHealthViewModel.factory(container),
    ),
) {
    val health by viewModel.health.collectAsStateWithLifecycle()
    val microphone = rememberMicrophonePermissionState()
    val notifications = rememberNotificationPermissionState()
    val context = LocalContext.current

    // A user who leaves to change a system setting comes back here; re-read on resume.
    LifecycleResumeEffect(microphone.granted, notifications.enabled) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    SensorHealthScreen(
        health = health,
        microphone = microphone,
        notifications = notifications,
        onEnable = viewModel::enable,
        onDisable = viewModel::disable,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onDismissError = viewModel::dismissError,
        onOpenBatterySettings = {
            context.launch(container.sensorEnvironment.batteryOptimisationSettingsIntent())
        },
        onOpenAppSettings = {
            context.launch(container.sensorEnvironment.appSettingsIntent())
        },
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun SensorHealthScreen(
    health: SensorHealth,
    microphone: MicrophonePermissionState,
    notifications: NotificationPermissionState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDismissError: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    SentinelScreen(title = "Sensor health", onNavigateBack = onNavigateBack) {
            HeadlineCard(health)

            if (health.resumeRequired) {
                ActionCard(
                    title = "Listening needs resuming",
                    body = resumeExplanation(health),
                    actionLabel = "Resume listening",
                    onAction = onResume,
                )
            }

            health.lastError?.takeIf { it.needsUserAction }?.let { error ->
                ActionCard(
                    title = "Needs attention",
                    body = error.describe(),
                    actionLabel = "Dismiss",
                    onAction = onDismissError,
                )
            }

            SectionCard("Status") {
                LabelledRow(
                    label = "Listening",
                    value = health.listeningLabel(),
                    emphasise = health.desiredMode == ListeningMode.LISTENING &&
                        !health.listening,
                )
                LabelledRow(
                    label = "Foreground service",
                    value = if (health.serviceRunning) "Running" else "Not running",
                    emphasise = health.desiredMode.isEnabled && !health.serviceRunning,
                )
                LabelledRow(
                    label = "Microphone permission",
                    value = health.microphone.label(),
                    emphasise = health.microphone != PermissionStatus.GRANTED,
                )
                LabelledRow(
                    label = "Notification permission",
                    value = health.notifications.label(),
                    emphasise = health.notifications == PermissionStatus.DENIED,
                )
                LabelledRow(
                    label = "Battery optimisation",
                    value = health.batteryOptimisation.label(),
                    emphasise = health.batteryOptimisation == BatteryOptimisationStatus.OPTIMISED,
                )
            }

            SectionCard("History") {
                LabelledRow("Last service start", health.lastServiceStartAtEpochMillis.asTime())
                LabelledRow("Last detected trigger", health.lastTriggerAtEpochMillis.asTime())
                LabelledRow("Last restart detected", health.lastBootAtEpochMillis.asTime())
                health.lastError?.let {
                    LabelledRow("Last error", it.atEpochMillis.asTime())
                }
            }

            if (health.microphone != PermissionStatus.GRANTED) {
                ActionCard(
                    title = "Microphone access is required",
                    body = "Sensor Mode cannot hear anything without it. Audio is analysed " +
                        "on this device and never recorded.",
                    actionLabel = if (microphone.deniedAfterRequest) {
                        "Open app settings"
                    } else {
                        "Grant microphone access"
                    },
                    onAction = if (microphone.deniedAfterRequest) {
                        microphone.openAppSettings
                    } else {
                        microphone.request
                    },
                )
            }

            if (health.notifications == PermissionStatus.DENIED) {
                ActionCard(
                    title = "Notifications are switched off",
                    body = "Listening still works, but the Pause and Resume buttons live in " +
                        "the notification, and without it you cannot tell the phone is on.",
                    actionLabel = if (notifications.needsRequest) {
                        "Allow notifications"
                    } else {
                        "Open app settings"
                    },
                    onAction = if (notifications.needsRequest) {
                        notifications.request
                    } else {
                        onOpenAppSettings
                    },
                )
            }

            if (health.batteryOptimisation == BatteryOptimisationStatus.OPTIMISED) {
                BatteryOptimisationCard(onOpenBatterySettings)
            }

            ShelfDivider(Modifier.padding(vertical = 4.dp))

            SensorModeControls(
                health = health,
                enabled = health.microphone == PermissionStatus.GRANTED,
                onEnable = onEnable,
                onDisable = onDisable,
                onPause = onPause,
                onResume = onResume,
            )
        }
}

@Composable
private fun HeadlineCard(health: SensorHealth) {
    val ready = health.readyForUnattendedUse
    val palette = Shelf.palette
    HeroCard {
        Text(
            text = when {
                !health.desiredMode.isEnabled -> "Sensor Mode is off"
                ready && health.listening -> "Listening, unattended"
                ready -> "Ready"
                else -> "Something needs your attention"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = when {
                !health.desiredMode.isEnabled -> palette.textDim
                ready -> palette.cyan
                else -> palette.warn
            },
        )
        Text(
            text = when {
                !health.desiredMode.isEnabled ->
                    "Turn Sensor Mode on to listen for claps with the screen off."

                ready && health.listening ->
                    "The screen can be off. Keep the phone plugged in."

                ready -> "Nothing is blocking unattended use."
                else -> "${health.attention.size} item(s) below need looking at."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textDim,
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    GlassCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Shelf.palette.warn,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textDim,
        )
        GradientButton(text = actionLabel, onClick = onAction)
    }
}

/**
 * Explains the trade-off rather than just demanding a tap.
 *
 * An exemption is genuinely optional: stock Android will not kill a foreground service
 * for being optimised. It matters on the manufacturer builds that do, and the honest
 * framing is "this helps on some phones", not "this is required".
 */
@Composable
private fun BatteryOptimisationCard(onOpenSettings: () -> Unit) {
    GlassCard {
        Text(
            text = "Battery optimisation is on",
            style = MaterialTheme.typography.titleMedium,
            color = Shelf.palette.text,
        )
        Text(
            text = "Stock Android will not stop a listening foreground service for this " +
                "reason, so Sensor Mode usually works fine as-is. Some manufacturers are " +
                "more aggressive and stop background apps anyway. If listening keeps " +
                "stopping on its own, exempting the app is the fix.",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textDim,
        )
        Text(
            text = "Android opens its own list — find Shelfit Sentinel and choose " +
                "\"Don't optimise\".",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textFaint,
        )
        GhostButton(text = "Open battery settings", onClick = onOpenSettings)
    }
}

@Composable
private fun SensorModeControls(
    health: SensorHealth,
    enabled: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (health.desiredMode) {
            ListeningMode.OFF -> GradientButton(
                text = "Turn on Sensor Mode",
                onClick = onEnable,
                enabled = enabled,
            )

            ListeningMode.LISTENING -> {
                if (health.serviceRunning) {
                    GhostButton(text = "Pause listening", onClick = onPause)
                }
                LinkButton(text = "Turn off Sensor Mode", onClick = onDisable)
            }

            ListeningMode.PAUSED -> {
                GradientButton(
                    text = "Resume listening",
                    onClick = onResume,
                    enabled = enabled,
                )
                LinkButton(text = "Turn off Sensor Mode", onClick = onDisable)
            }
        }
    }
}

private fun resumeExplanation(health: SensorHealth): String =
    if (health.lastBootAtEpochMillis != null) {
        "The phone restarted. Android does not let an app start microphone monitoring " +
            "by itself after a reboot, so listening needs one tap from you."
    } else {
        "Sensor Mode is on but nothing is listening — the app was updated, or its " +
            "process was stopped. One tap starts it again."
    }

private fun SensorHealth.listeningLabel(): String = when {
    listening -> "Yes"
    desiredMode == ListeningMode.PAUSED -> "Paused"
    desiredMode == ListeningMode.LISTENING -> "No — needs resuming"
    else -> "No"
}

private fun PermissionStatus.label(): String = when (this) {
    PermissionStatus.GRANTED -> "Granted"
    PermissionStatus.DENIED -> "Not granted"
    PermissionStatus.NOT_REQUIRED -> "Not needed"
}

private fun BatteryOptimisationStatus.label(): String = when (this) {
    BatteryOptimisationStatus.EXEMPT -> "Exempt"
    BatteryOptimisationStatus.OPTIMISED -> "On — may interfere"
    BatteryOptimisationStatus.UNKNOWN -> "Unknown"
}

private fun SensorModeError.describe(): String = when (kind) {
    SensorModeError.Kind.MICROPHONE_PERMISSION_REVOKED ->
        "Microphone access was removed while Sensor Mode was on, so listening stopped."

    SensorModeError.Kind.FOREGROUND_START_BLOCKED ->
        "Android refused to start listening. This happens when the request did not come " +
            "from the app being open. Try again from here."

    SensorModeError.Kind.MICROPHONE_UNAVAILABLE ->
        "The microphone was unavailable — usually another app using it. " +
            "Sensor Mode retries on its own."

    SensorModeError.Kind.AUDIO_FAILURE ->
        "Microphone capture failed" + (detail?.let { ": $it" } ?: ".")
}

private fun Long?.asTime(): String =
    this?.let { TIME_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
        ?: "—"

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm:ss")

private fun Context.launch(intent: android.content.Intent) {
    runCatching { startActivity(intent) }
}

@Preview(showBackground = true)
@Composable
private fun SensorHealthScreenPreview() {
    SentinelTheme {
        SensorHealthScreen(
            health = SensorHealth(
                desiredMode = ListeningMode.LISTENING,
                serviceRunning = true,
                listening = true,
                microphone = PermissionStatus.GRANTED,
                notifications = PermissionStatus.GRANTED,
                batteryOptimisation = BatteryOptimisationStatus.OPTIMISED,
                lastServiceStartAtEpochMillis = 1_700_000_000_000L,
                lastTriggerAtEpochMillis = 1_700_000_100_000L,
            ),
            microphone = MicrophonePermissionState(true, false, {}, {}),
            notifications = NotificationPermissionState(true, false, {}),
            onEnable = {},
            onDisable = {},
            onPause = {},
            onResume = {},
            onDismissError = {},
            onOpenBatterySettings = {},
            onOpenAppSettings = {},
            onNavigateBack = {},
        )
    }
}
