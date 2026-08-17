package com.shelfit.sentinel.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.trigger.audio.CalibrationStage
import com.shelfit.sentinel.trigger.audio.CalibrationQuality
import com.shelfit.sentinel.trigger.audio.ClapCalibration
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
import com.shelfit.sentinel.trigger.audio.ClapProfile
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.LevelMeter
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.formatDecibels
import com.shelfit.sentinel.ui.components.formatMultiple
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme

@Composable
fun CalibrationRoute(
    container: AppContainer,
    onFinished: () -> Unit,
    viewModel: CalibrationViewModel = viewModel(
        factory = CalibrationViewModel.factory(container),
    ),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val permission = rememberMicrophonePermissionState()

    CalibrationScreen(
        step = step,
        diagnostics = diagnostics,
        permission = permission,
        onStart = viewModel::start,
        onBeginTrial = viewModel::beginTrial,
        onEndTrial = viewModel::endTrial,
        onSave = viewModel::save,
        onDiscard = viewModel::discard,
        onFinished = onFinished,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    step: CalibrationStep,
    diagnostics: ClapDiagnostics,
    permission: MicrophonePermissionState,
    onStart: () -> Unit,
    onBeginTrial: () -> Unit,
    onEndTrial: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onFinished: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrate double clap") },
                navigationIcon = {
                    IconButton(onClick = onFinished) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                CalibrationStep.Intro -> IntroStep(permission, onStart)
                is CalibrationStep.Measuring -> MeasuringStep(step.stage, onDiscard)
                is CalibrationStep.Review ->
                    ReviewStep(step.calibration, step.profile, onBeginTrial, onSave, onStart)

                is CalibrationStep.Trial ->
                    TrialStep(diagnostics, onSave, onEndTrial)

                is CalibrationStep.Failed -> FailedStep(step.reason, onStart, onFinished)
                CalibrationStep.Saved -> SavedStep(onFinished)
            }

            Text(
                text = "Calibration measures levels and timings only. No audio is " +
                    "recorded, and only the resulting numbers are saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IntroStep(permission: MicrophonePermissionState, onStart: () -> Unit) {
    SectionCard("What happens") {
        Step(1, "The room is measured for a few seconds. Stay quiet.")
        Step(2, "You clap ${ClapCalibration.PREFERRED_SAMPLES} times, normally, from " +
            "where you will actually be standing.")
        Step(3, "Recommended settings are calculated and shown to you.")
        Step(4, "You try them out before anything is saved.")
    }

    Text(
        text = "Stand where you will normally be, and leave the phone where it will " +
            "normally sit. Calibrating from a different distance is the most common " +
            "reason detection disappoints afterwards.",
        style = MaterialTheme.typography.bodyMedium,
    )

    if (permission.granted) {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start calibration")
        }
    } else {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Microphone access is required",
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
}

@Composable
private fun Step(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MeasuringStep(stage: CalibrationStage, onDiscard: () -> Unit) {
    when (stage) {
        is CalibrationStage.MeasuringAmbient -> {
            Instruction("Measuring the room", "Stay quiet for a moment.")
            LinearProgressIndicator(
                progress = { stage.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            LevelMeter(level = stage.level)
            LabelledRow("Room level", formatDecibels(stage.level))
        }

        is CalibrationStage.CollectingClaps -> {
            Instruction(
                title = "Clap now",
                body = "Clap ${stage.required} times at a comfortable pace, the way " +
                    "you would to switch on a light.",
            )
            ClapProgress(stage.collected, stage.required)
            LevelMeter(level = stage.level)
            LabelledRow("Claps heard", "${stage.collected} of ${stage.required}")
            LabelledRow(
                "Time remaining",
                "${(stage.remainingMillis / MILLIS_PER_SECOND).coerceAtLeast(0)} s",
            )
        }

        else -> Unit
    }

    OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun Instruction(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One filled dot per clap heard, so progress is obvious at arm's length. */
@Composable
private fun ClapProgress(collected: Int, required: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(required) { index ->
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .background(
                        color = if (index < collected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun ReviewStep(
    calibration: ClapCalibration,
    profile: ClapProfile,
    onBeginTrial: () -> Unit,
    onSave: () -> Unit,
    onRedo: () -> Unit,
) {
    QualityCard(calibration)

    SectionCard("What was measured") {
        LabelledRow("Room level", formatDecibels(calibration.ambientRms))
        LabelledRow("Room peaks", formatDecibels(calibration.ambientPeak))
        LabelledRow("Typical clap", formatDecibels(calibration.clapPeakMedian))
        LabelledRow("Softest clap", formatDecibels(calibration.clapPeakMinimum))
        LabelledRow("Clap decay", "${calibration.clapTransientMaximumMillis} ms")
        LabelledRow("Claps used", calibration.sampleCount.toString())
    }

    SectionCard("Recommended settings") {
        LabelledRow("Minimum clap level", formatDecibels(profile.minPeakAmplitude))
        LabelledRow("Above background", formatMultiple(profile.minAmbientRatio))
        LabelledRow("Brightness floor", "%.3f".format(profile.minHighFrequencyRatio))
        LabelledRow("Decay window", "${profile.maxTransientMillis} ms")
    }

    Button(onClick = onBeginTrial, modifier = Modifier.fillMaxWidth()) {
        Text("Try it out")
    }
    OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text("Save without testing")
    }
    TextButton(onClick = onRedo, modifier = Modifier.fillMaxWidth()) {
        Text("Measure again")
    }
}

@Composable
private fun QualityCard(calibration: ClapCalibration) {
    val quality = calibration.quality
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (quality) {
                CalibrationQuality.GOOD -> MaterialTheme.colorScheme.primaryContainer
                CalibrationQuality.MARGINAL -> MaterialTheme.colorScheme.tertiaryContainer
                CalibrationQuality.POOR -> MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when (quality) {
                    CalibrationQuality.GOOD -> "Good separation"
                    CalibrationQuality.MARGINAL -> "Usable, but tight"
                    CalibrationQuality.POOR -> "Poor separation"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Your claps measured ${calibration.headroomDecibels.toInt()} dB " +
                    "above the room.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = when (quality) {
                    CalibrationQuality.GOOD ->
                        "Detection should be reliable here."

                    CalibrationQuality.MARGINAL ->
                        "Expect the occasional miss, or the occasional false trigger. " +
                            "Clapping closer to the phone, or moving it away from a " +
                            "noise source, would help."

                    CalibrationQuality.POOR ->
                        "The room is nearly as loud as your claps, so no threshold " +
                            "will work well. Try again somewhere quieter, or place " +
                            "the phone closer to where you clap."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TrialStep(
    diagnostics: ClapDiagnostics,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Instruction(
        title = "Try a double clap",
        body = "The new settings are running but not saved. Clap twice and see whether " +
            "it registers.",
    )

    Card(
        modifier = Modifier.fillMaxWidth().height(TRIAL_BANNER_HEIGHT),
        colors = CardDefaults.cardColors(
            containerColor = if (diagnostics.detectionCount > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (diagnostics.detectionCount > 0) {
                        "Detected ${diagnostics.detectionCount}x"
                    } else {
                        "Listening…"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (diagnostics.detectionCount > 0) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                diagnostics.lastGapMillis?.let { gap ->
                    Text(
                        "$gap ms apart",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }

    LevelMeter(
        level = diagnostics.level,
        background = diagnostics.noiseFloor,
        threshold = diagnostics.effectiveMinPeak,
    )
    LabelledRow("Claps accepted", diagnostics.candidateCount.toString())

    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text("Works — save it")
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text("Back to results")
    }
}

@Composable
private fun FailedStep(reason: String, onRetry: () -> Unit, onFinished: () -> Unit) {
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
                "Calibration did not finish",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
    TextButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("Give up") }
}

@Composable
private fun SavedStep(onFinished: () -> Unit) {
    SectionCard("Saved") {
        Text(
            "Detection now uses settings measured on this phone, in this room.",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "Recalibrate if you move the phone, change rooms, or the background " +
                "noise changes for good.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

private const val MILLIS_PER_SECOND = 1_000L
private val DOT_SIZE = 16.dp
private val TRIAL_BANNER_HEIGHT = 120.dp

@Preview(showBackground = true)
@Composable
private fun CalibrationReviewPreview() {
    SentinelTheme {
        CalibrationScreen(
            step = CalibrationStep.Review(
                calibration = ClapCalibration(
                    capturedAtEpochMillis = 0L,
                    sampleCount = 5,
                    ambientRms = 0.002f,
                    ambientPeak = 0.01f,
                    clapPeakMedian = 0.42f,
                    clapPeakMinimum = 0.3f,
                    clapAmbientRatioMinimum = 60f,
                    clapCrestFactorMinimum = 3.4f,
                    clapHighFrequencyRatioMinimum = 0.5f,
                    clapTransientMaximumMillis = 64L,
                ),
                profile = ClapProfile(),
            ),
            diagnostics = ClapDiagnostics(),
            permission = MicrophonePermissionState(true, false, {}, {}),
            onStart = {},
            onBeginTrial = {},
            onEndTrial = {},
            onSave = {},
            onDiscard = {},
            onFinished = {},
        )
    }
}
