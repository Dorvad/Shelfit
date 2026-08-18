package com.shelfit.sentinel.ui.claplab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import com.shelfit.sentinel.core.trigger.TriggerState
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
import com.shelfit.sentinel.trigger.audio.ClapProfile
import com.shelfit.sentinel.trigger.audio.ClapRejection
import com.shelfit.sentinel.trigger.audio.DoubleClapPhase
import com.shelfit.sentinel.trigger.audio.SensitivityLevel
import com.shelfit.sentinel.ui.components.GhostButton
import com.shelfit.sentinel.ui.components.GlassCard
import com.shelfit.sentinel.ui.components.GradientButton
import com.shelfit.sentinel.ui.components.HeroCard
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.LinkButton
import com.shelfit.sentinel.ui.components.SentinelScreen
import com.shelfit.sentinel.ui.components.ShelfDivider
import com.shelfit.sentinel.ui.components.ShelfSegmented
import com.shelfit.sentinel.ui.components.ShelfSwitch
import com.shelfit.sentinel.ui.components.LevelMeter
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.formatConfidence
import com.shelfit.sentinel.ui.components.formatDecibels
import com.shelfit.sentinel.ui.components.formatMultiple
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme
import com.shelfit.sentinel.ui.theme.Shelf
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        onSensitivityChange = viewModel::setSensitivity,
        onHapticFeedbackChange = viewModel::setHapticFeedbackEnabled,
        onClearLog = viewModel::clearLog,
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun ClapLabScreen(
    uiState: ClapLabUiState,
    permission: MicrophonePermissionState,
    onToggleListening: () -> Unit,
    onSensitivityChange: (SensitivityLevel) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
    onClearLog: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    SentinelScreen(title = "Clap detector test", onNavigateBack = onNavigateBack) {
            if (!permission.granted) {
                PermissionCard(permission)
            }

            DetectionBanner(uiState.diagnostics)

            LevelCard(uiState.diagnostics, uiState.listening)

            StateCard(uiState)

            TuningCard(
                sensitivity = uiState.sensitivity,
                hapticFeedbackEnabled = uiState.hapticFeedbackEnabled,
                onSensitivityChange = onSensitivityChange,
                onHapticFeedbackChange = onHapticFeedbackChange,
            )

            ThresholdCard(uiState.profile, uiState.calibrated)

            EventLogCard(uiState.log, onClearLog)

            if (uiState.listening) {
                GhostButton(text = "Stop detection", onClick = onToggleListening)
            } else {
                GradientButton(
                    text = "Start detection",
                    onClick = onToggleListening,
                    enabled = permission.granted,
                )
            }

            Text(
                text = "Audio is analysed in memory and discarded. Nothing is " +
                    "recorded or sent anywhere, and the log holds numbers only.",
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
}

@Composable
private fun PermissionCard(permission: MicrophonePermissionState) {
    GlassCard {
        Text(
            text = "Microphone access is required",
            style = MaterialTheme.typography.titleMedium,
            color = Shelf.palette.warn,
        )
        if (permission.deniedAfterRequest) {
            GradientButton(text = "Open app settings", onClick = permission.openAppSettings)
        } else {
            GradientButton(text = "Grant microphone access", onClick = permission.request)
        }
    }
}

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

    val palette = Shelf.palette
    HeroCard {
        Column(
            modifier = Modifier.fillMaxWidth().height(BANNER_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (flashing) "DOUBLE CLAP DETECTED" else "Waiting for a double clap",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = if (flashing) palette.cyan else palette.textDim,
            )
            diagnostics.lastGapMillis?.let { gap ->
                Text(
                    text = "Gap between claps: $gap ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (flashing) palette.text else palette.textDim,
                )
            }
        }
    }
}

@Composable
private fun LevelCard(diagnostics: ClapDiagnostics, listening: Boolean) {
    SectionCard("Microphone level") {
        LevelMeter(
            level = diagnostics.level,
            background = diagnostics.noiseFloor,
            threshold = diagnostics.effectiveMinPeak,
        )
        LabelledRow("Level", formatDecibels(diagnostics.level))
        LabelledRow("Background (red)", formatDecibels(diagnostics.noiseFloor))
        LabelledRow("Clap gate (purple)", formatDecibels(diagnostics.effectiveMinPeak))
        LabelledRow("Background peak", formatDecibels(diagnostics.ambientPeak))
        if (diagnostics.suppressingBurst) {
            LabelledRow(
                label = "Burst suppression",
                value = "active — too many transients",
                emphasise = true,
            )
        }
        if (!listening) {
            Text(
                text = "Not listening.",
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.textDim,
            )
        }
    }
}

@Composable
private fun StateCard(uiState: ClapLabUiState) {
    val diagnostics = uiState.diagnostics
    SectionCard("Detector") {
        LabelledRow("Status", uiState.detectorState.label())
        LabelledRow("Gesture", diagnostics.phase.label())
        ShelfDivider(Modifier.padding(vertical = 4.dp))
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
            ShelfDivider(Modifier.padding(vertical = 4.dp))
            LabelledRow("Rule fired", outcome.ruleName)
            LabelledRow("Action result", outcome.result.label())
        }
    }
}

@Composable
private fun TuningCard(
    sensitivity: SensitivityLevel,
    hapticFeedbackEnabled: Boolean,
    onSensitivityChange: (SensitivityLevel) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
) {
    SectionCard("Tuning") {
        ShelfSegmented(
            options = SensitivityLevel.entries.map {
                it.name.lowercase().replaceFirstChar(Char::titlecase)
            },
            selectedIndex = SensitivityLevel.entries.indexOf(sensitivity),
            onSelect = { onSensitivityChange(SensitivityLevel.entries[it]) },
        )
        Text(
            text = "Applied immediately, which restarts capture.",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textDim,
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
                color = Shelf.palette.text,
                modifier = Modifier.weight(1f),
            )
            ShelfSwitch(checked = hapticFeedbackEnabled, onCheckedChange = onHapticFeedbackChange)
        }
    }
}

/**
 * The numeric thresholds actually in force, after calibration and sensitivity have
 * both been applied. Read-only: these are derived values, and the way to change them
 * is to recalibrate or move the sensitivity, not to edit them behind the model's back.
 */
@Composable
private fun ThresholdCard(profile: ClapProfile, calibrated: Boolean) {
    SectionCard("Thresholds in force") {
        Text(
            text = if (calibrated) {
                "Derived from your calibration, then scaled by sensitivity."
            } else {
                "Generic defaults, scaled by sensitivity. Calibrate for better numbers."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textDim,
        )
        ShelfDivider(Modifier.padding(vertical = 4.dp))
        LabelledRow("Min clap level", formatDecibels(profile.minPeakAmplitude))
        LabelledRow("Min above background", formatMultiple(profile.minAmbientRatio))
        LabelledRow("Min attack", formatMultiple(profile.minAttackRatio))
        LabelledRow("Min crest factor", formatMultiple(profile.minCrestFactor))
        LabelledRow("Min brightness", "%.3f".format(profile.minHighFrequencyRatio))
        LabelledRow("Max clap duration", "${profile.maxTransientMillis} ms")
        LabelledRow("Quiet needed before", "${profile.quietBeforeMillis} ms")
        LabelledRow("Quiet needed after", "${profile.quietAfterMillis} ms")
        LabelledRow(
            "Burst allowance",
            "${profile.maxTransientsPerWindow} per ${profile.transientWindowMillis} ms",
        )
        LabelledRow("Adaptive gate ceiling", formatMultiple(profile.adaptiveRangeUp))
    }
}

@Composable
private fun EventLogCard(log: List<DiagnosticEvent>, onClear: () -> Unit) {
    SectionCard("Event log") {
        if (log.isEmpty()) {
            Text(
                text = "Nothing yet. Start detection and clap.",
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.textDim,
            )
            return@SectionCard
        }

        log.take(LOG_LINES).forEach { event ->
            Text(
                text = buildString {
                    append(event.atEpochMillis.asClockTime())
                    append("  ")
                    append(event.kind.label())
                    event.detail?.let {
                        append("  ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (event.kind == DiagnosticEvent.Kind.DOUBLE_CLAP_DETECTED) {
                    Shelf.palette.cyan
                } else {
                    Shelf.palette.textDim
                },
            )
        }

        LinkButton(text = "Clear log", onClick = onClear)
    }
}

private fun Long.asClockTime(): String =
    CLOCK_FORMAT.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun DiagnosticEvent.Kind.label(): String = when (this) {
    DiagnosticEvent.Kind.LISTENING_STARTED -> "Listening started"
    DiagnosticEvent.Kind.LISTENING_STOPPED -> "Listening stopped"
    DiagnosticEvent.Kind.DETECTOR_FAILED -> "Detector failed"
    DiagnosticEvent.Kind.CLAP_CANDIDATE -> "Clap candidate"
    DiagnosticEvent.Kind.CLAP_REJECTED -> "Sound rejected"
    DiagnosticEvent.Kind.AWAITING_SECOND_CLAP -> "Waiting for second clap"
    DiagnosticEvent.Kind.DOUBLE_CLAP_DETECTED -> "DOUBLE CLAP DETECTED"
    DiagnosticEvent.Kind.SECOND_CLAP_TIMED_OUT -> "Second clap timed out"
    DiagnosticEvent.Kind.COOLDOWN_ENDED -> "Cooldown ended"
    DiagnosticEvent.Kind.TRANSIENTS_SUPPRESSED -> "Burst suppressed"
    DiagnosticEvent.Kind.AMBIENT_THRESHOLD_RAISED -> "Threshold raised"
    DiagnosticEvent.Kind.CALIBRATION_SAVED -> "Calibration saved"
    DiagnosticEvent.Kind.CALIBRATION_CLEARED -> "Calibration cleared"
    DiagnosticEvent.Kind.SENSOR_MODE_LISTENING -> "Sensor Mode listening"
    DiagnosticEvent.Kind.SENSOR_MODE_PAUSED -> "Sensor Mode paused"
    DiagnosticEvent.Kind.SERVICE_START_BLOCKED -> "Service start refused"
    DiagnosticEvent.Kind.CAPTURE_RECOVERING -> "Capture recovering"
    DiagnosticEvent.Kind.CAPTURE_RECOVERED -> "Capture recovered"
    DiagnosticEvent.Kind.MICROPHONE_PERMISSION_LOST -> "Microphone permission lost"
    DiagnosticEvent.Kind.RESUME_REQUIRED -> "Resume required"
}

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
    ClapRejection.TOO_MANY_TRANSIENTS -> "Too many transients (burst)"
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
    is ActionResult.Partial -> "Partly done — $message"
}

private const val FLASH_MILLIS = 1_600L
private const val LOG_LINES = 20
private val BANNER_HEIGHT = 120.dp

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
                    effectiveMinPeak = 0.08f,
                    phase = DoubleClapPhase.AwaitingSecondClap(1_000L, 1_900L),
                    candidateCount = 3,
                    detectionCount = 1,
                    lastGapMillis = 240L,
                ),
                log = listOf(
                    DiagnosticEvent(0L, DiagnosticEvent.Kind.DOUBLE_CLAP_DETECTED, "240 ms apart"),
                    DiagnosticEvent(0L, DiagnosticEvent.Kind.AWAITING_SECOND_CLAP),
                    DiagnosticEvent(0L, DiagnosticEvent.Kind.CLAP_CANDIDATE, "confidence 0.91"),
                ),
            ),
            permission = MicrophonePermissionState(true, false, {}, {}),
            onToggleListening = {},
            onSensitivityChange = {},
            onHapticFeedbackChange = {},
            onClearLog = {},
            onNavigateBack = {},
        )
    }
}
