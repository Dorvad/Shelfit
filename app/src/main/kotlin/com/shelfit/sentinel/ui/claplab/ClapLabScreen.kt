package com.shelfit.sentinel.ui.claplab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.diagnostics.DiagnosticEvent
import com.shelfit.sentinel.core.trigger.TriggerState
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
import com.shelfit.sentinel.trigger.audio.ClapProfile
import com.shelfit.sentinel.trigger.audio.ClapRejection
import com.shelfit.sentinel.trigger.audio.DoubleClapPhase
import com.shelfit.sentinel.trigger.audio.SensitivityLevel
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.LevelMeter
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.formatConfidence
import com.shelfit.sentinel.ui.components.formatDecibels
import com.shelfit.sentinel.ui.components.formatMultiple
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme
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

@OptIn(ExperimentalMaterial3Api::class)
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
                onSensitivityChange = onSensitivityChange,
                onHapticFeedbackChange = onHapticFeedbackChange,
            )

            ThresholdCard(uiState.profile, uiState.calibrated)

            EventLogCard(uiState.log, onClearLog)

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
                    "recorded or sent anywhere, and the log holds numbers only.",
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
                Button(onClick = permission.openAppSettings) { Text("Open app settings") }
            } else {
                Button(onClick = permission.request) { Text("Grant microphone access") }
            }
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(BANNER_HEIGHT),
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
                diagnostics.lastGapMillis?.let { gap ->
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TuningCard(
    sensitivity: SensitivityLevel,
    hapticFeedbackEnabled: Boolean,
    onSensitivityChange: (SensitivityLevel) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
) {
    SectionCard("Tuning") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SensitivityLevel.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = level == sensitivity,
                    onClick = { onSensitivityChange(level) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SensitivityLevel.entries.size,
                    ),
                    label = { Text(level.name.lowercase().replaceFirstChar(Char::titlecase)) },
                )
            }
        }
        Text(
            text = "Applied immediately, which restarts capture.",
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        TextButton(onClick = onClear) { Text("Clear log") }
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
