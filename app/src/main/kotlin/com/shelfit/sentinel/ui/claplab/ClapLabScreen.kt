package com.shelfit.sentinel.ui.claplab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.trigger.TriggerState
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
import com.shelfit.sentinel.trigger.audio.ClapRejection
import com.shelfit.sentinel.trigger.audio.DoubleClapPhase
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme
import kotlinx.coroutines.delay
import kotlin.math.log10

@Composable
fun ClapLabRoute(
    container: AppContainer,
    onNavigateBack: () -> Unit,
    viewModel: ClapLabViewModel = viewModel(factory = ClapLabViewModel.factory(container)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permission = rememberMicrophonePermissionState()

    ClapLabScreen(
        uiState = uiState,
        permission = permission,
        onToggleListening = viewModel::toggleListening,
        onSensitivityCommitted = viewModel::commitSensitivity,
        onHapticFeedbackChange = viewModel::setHapticFeedbackEnabled,
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClapLabScreen(
    uiState: ClapLabUiState,
    permission: MicrophonePermissionState,
    onToggleListening: () -> Unit,
    onSensitivityCommitted: (Float) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clap detector test") },
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
            if (!permission.granted) {
                PermissionCard(permission)
            }

            DetectionBanner(uiState.diagnostics)

            LevelCard(uiState.diagnostics, uiState.listening)

            StateCard(uiState)

            TuningCard(
                sensitivity = uiState.sensitivity,
                hapticFeedbackEnabled = uiState.hapticFeedbackEnabled,
                onSensitivityCommitted = onSensitivityCommitted,
                onHapticFeedbackChange = onHapticFeedbackChange,
            )

            if (uiState.listening) {
                OutlinedButton(
                    onClick = onToggleListening,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop detection") }
            } else {
                Button(
                    onClick = onToggleListening,
                    enabled = permission.granted,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start detection") }
            }

            Text(
                text = "Audio is analysed in memory and discarded. Nothing is " +
                    "recorded or sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PermissionCard(permission: MicrophonePermissionState) {
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
                text = "Microphone access is required",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (permission.deniedAfterRequest) {
                Text(
                    text = "Access was declined. Grant it from the app's settings page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(onClick = permission.openAppSettings) { Text("Open app settings") }
            } else {
                Button(onClick = permission.request) { Text("Grant microphone access") }
            }
        }
    }
}

/**
 * The unmissable part: fills with colour for a moment on every confirmed gesture and
 * shows how far apart the two claps were.
 */
@Composable
private fun DetectionBanner(diagnostics: ClapDiagnostics) {
    var flashing by remember { mutableStateOf(false) }

    LaunchedEffect(diagnostics.detectionCount) {
        if (diagnostics.detectionCount > 0) {
            flashing = true
            delay(FLASH_MILLIS)
            flashing = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (flashing) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (flashing) "DOUBLE CLAP DETECTED" else "Waiting for a double clap",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (flashing) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = if (flashing) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                val gap = diagnostics.lastGapMillis
                if (gap != null) {
                    Text(
                        text = "Gap between claps: $gap ms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (flashing) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelCard(diagnostics: ClapDiagnostics, listening: Boolean) {
    SectionCard("Microphone level") {
        LevelMeter(
            level = diagnostics.level,
            noiseFloor = diagnostics.noiseFloor,
        )
        LabelledRow("Level", formatDecibels(diagnostics.level))
        LabelledRow("Background", formatDecibels(diagnostics.noiseFloor))
        LabelledRow("Frame peak", formatDecibels(diagnostics.peak))
        if (!listening) {
            Text(
                text = "Not listening.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Level on a decibel scale, with a tick showing where the tracked background sits.
 * A clap should read as a wide gap between the two.
 */
@Composable
private fun LevelMeter(level: Float, noiseFloor: Float) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        val trackWidth = maxWidth

        Box(
            modifier = Modifier
                .fillMaxWidth(meterFraction(level))
                .height(20.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp),
                ),
        )

        Box(
            modifier = Modifier
                .offset(x = trackWidth * meterFraction(noiseFloor))
                .width(2.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.error),
        )
    }
}

@Composable
private fun StateCard(uiState: ClapLabUiState) {
    val diagnostics = uiState.diagnostics
    SectionCard("Detector") {
        LabelledRow("Status", uiState.detectorState.label())
        LabelledRow("Gesture", diagnostics.phase.label())
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        LabelledRow("Claps accepted", diagnostics.candidateCount.toString())
        LabelledRow("Double claps", diagnostics.detectionCount.toString())
        LabelledRow(
            label = "Last clap confidence",
            value = diagnostics.lastCandidateConfidence?.let { formatConfidence(it) } ?: "—",
        )
        LabelledRow(
            label = "Rejected sound",
            value = diagnostics.lastRejection?.label() ?: "—",
            emphasise = diagnostics.lastRejection != null,
        )
        uiState.lastOutcome?.let { outcome ->
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LabelledRow("Rule fired", outcome.ruleName)
            LabelledRow("Action result", outcome.result.label())
        }
    }
}

@Composable
private fun TuningCard(
    sensitivity: Float,
    hapticFeedbackEnabled: Boolean,
    onSensitivityCommitted: (Float) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
) {
    // Held locally while dragging: applying a value restarts capture, so it is only
    // committed when the finger lifts.
    var pending by remember(sensitivity) { mutableFloatStateOf(sensitivity) }

    SectionCard("Tuning") {
        LabelledRow("Sensitivity", "%.2f".format(pending))
        Slider(
            value = pending,
            onValueChange = { pending = it },
            onValueChangeFinished = { onSensitivityCommitted(pending) },
            valueRange = 0f..1f,
        )
        Text(
            text = "Higher reacts to quieter and more distant claps, and lets more " +
                "false triggers through. Applied when you release the slider, which " +
                "restarts capture.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Vibrate on detection",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = hapticFeedbackEnabled, onCheckedChange = onHapticFeedbackChange)
        }
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
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

/** Maps a 0f..1f amplitude onto the meter via decibels, which matches how loudness reads. */
private fun meterFraction(amplitude: Float): Float {
    if (amplitude <= 0f) return 0f
    val decibels = 20f * log10(amplitude)
    return ((decibels - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
}

private fun formatDecibels(amplitude: Float): String {
    if (amplitude <= 0f) return "—"
    return "%.0f dB".format(20f * log10(amplitude))
}

private fun formatConfidence(confidence: Float): String = "%.2f".format(confidence)

private fun DoubleClapPhase.label(): String = when (this) {
    DoubleClapPhase.Idle -> "Idle"
    is DoubleClapPhase.AwaitingSecondClap -> "First clap heard — waiting for the second"
    is DoubleClapPhase.CoolingDown -> "Cooling down"
}

private fun ClapRejection.label(): String = when (this) {
    ClapRejection.NO_QUIET_BEFORE -> "Background was not quiet"
    ClapRejection.SLOW_ATTACK -> "Attack too gradual"
    ClapRejection.NOT_IMPULSIVE -> "Not impulsive enough"
    ClapRejection.LOW_FREQUENCY_RUMBLE -> "Too low-pitched (thud)"
    ClapRejection.TRANSIENT_TOO_LONG -> "Lasted too long"
    ClapRejection.NO_QUIET_AFTER -> "No quiet afterwards"
}

private fun TriggerState.label(): String = when (this) {
    TriggerState.Idle -> "Stopped"
    TriggerState.Starting -> "Starting"
    TriggerState.Active -> "Listening"
    is TriggerState.Failed -> "Failed: $message"
    is TriggerState.Unavailable -> when (reason) {
        TriggerState.Reason.MISSING_PERMISSION -> "Microphone permission required"
        TriggerState.Reason.MISSING_SENSOR -> "No microphone"
        TriggerState.Reason.NOT_IMPLEMENTED -> message ?: "Not implemented"
        TriggerState.Reason.DISABLED -> "Disabled"
    }
}

private fun ActionResult.label(): String = when (this) {
    ActionResult.Success -> "Success"
    is ActionResult.Skipped -> "Skipped — $reason"
    is ActionResult.Failure -> "Failed — $message"
}

private const val FLASH_MILLIS = 1_600L
private const val METER_FLOOR_DB = -70f

@Preview(showBackground = true)
@Composable
private fun ClapLabScreenPreview() {
    SentinelTheme {
        ClapLabScreen(
            uiState = ClapLabUiState(
                listening = true,
                detectorState = TriggerState.Active,
                diagnostics = ClapDiagnostics(
                    listening = true,
                    level = 0.02f,
                    peak = 0.05f,
                    noiseFloor = 0.01f,
                    phase = DoubleClapPhase.AwaitingSecondClap(1_000L, 1_900L),
                    candidateCount = 3,
                    detectionCount = 1,
                    lastGapMillis = 240L,
                ),
            ),
            permission = MicrophonePermissionState(
                granted = true,
                deniedAfterRequest = false,
                request = {},
                openAppSettings = {},
            ),
            onToggleListening = {},
            onSensitivityCommitted = {},
            onHapticFeedbackChange = {},
            onNavigateBack = {},
        )
    }
}
