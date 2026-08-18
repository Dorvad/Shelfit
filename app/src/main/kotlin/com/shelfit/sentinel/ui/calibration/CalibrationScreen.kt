package com.shelfit.sentinel.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.trigger.audio.CalibrationQuality
import com.shelfit.sentinel.trigger.audio.CalibrationStage
import com.shelfit.sentinel.trigger.audio.ClapCalibration
import com.shelfit.sentinel.trigger.audio.ClapDiagnostics
import com.shelfit.sentinel.trigger.audio.ClapProfile
import com.shelfit.sentinel.ui.components.GhostButton
import com.shelfit.sentinel.ui.components.GlassCard
import com.shelfit.sentinel.ui.components.GradientButton
import com.shelfit.sentinel.ui.components.HeroCard
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.LevelMeter
import com.shelfit.sentinel.ui.components.LinkButton
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.SentinelScreen
import com.shelfit.sentinel.ui.components.ShelfDivider
import com.shelfit.sentinel.ui.components.formatDecibels
import com.shelfit.sentinel.ui.components.formatMultiple
import com.shelfit.sentinel.ui.permission.MicrophonePermissionState
import com.shelfit.sentinel.ui.permission.rememberMicrophonePermissionState
import com.shelfit.sentinel.ui.theme.SentinelTheme
import com.shelfit.sentinel.ui.theme.Shelf

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
    SentinelScreen(title = "Calibrate double clap", onNavigateBack = onFinished) {
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
            color = Shelf.palette.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
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
        color = Shelf.palette.textDim,
    )

    if (permission.granted) {
        GradientButton(text = "Start calibration", onClick = onStart)
    } else {
        GlassCard {
            Text(
                "Microphone access is required",
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
}

@Composable
private fun Step(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.titleMedium,
            color = Shelf.palette.accent,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Shelf.palette.text)
    }
}

@Composable
private fun MeasuringStep(stage: CalibrationStage, onDiscard: () -> Unit) {
    when (stage) {
        is CalibrationStage.MeasuringAmbient -> {
            Instruction("Measuring the room", "Stay quiet for a moment.")
            MeasureProgressBar(stage.fraction)
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

    GhostButton(text = "Cancel", onClick = onDiscard)
}

/** Thin gradient progress track, matching the level meter's language. */
@Composable
private fun MeasureProgressBar(fraction: Float) {
    val palette = Shelf.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0x1A7EA6FF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.accentStart, palette.accentEnd),
                    ),
                ),
        )
    }
}

@Composable
private fun Instruction(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = Shelf.palette.text,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.textDim,
        )
    }
}

/** One filled dot per clap heard, so progress is obvious at arm's length. */
@Composable
private fun ClapProgress(collected: Int, required: Int) {
    val palette = Shelf.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(required) { index ->
            val filled = index < collected
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .clip(CircleShape)
                    .background(
                        if (filled) {
                            Brush.linearGradient(
                                listOf(palette.accentStart, palette.accentEnd),
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(Color(0x1F7EA6FF), Color(0x1F7EA6FF)),
                            )
                        },
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

    GradientButton(text = "Try it out", onClick = onBeginTrial)
    GhostButton(text = "Save without testing", onClick = onSave)
    LinkButton(text = "Measure again", onClick = onRedo)
}

@Composable
private fun QualityCard(calibration: ClapCalibration) {
    val palette = Shelf.palette
    val quality = calibration.quality
    val titleColor = when (quality) {
        CalibrationQuality.GOOD -> palette.cyan
        CalibrationQuality.MARGINAL -> palette.warn
        CalibrationQuality.POOR -> palette.warn
    }
    HeroCard {
        Text(
            text = when (quality) {
                CalibrationQuality.GOOD -> "Good separation"
                CalibrationQuality.MARGINAL -> "Usable, but tight"
                CalibrationQuality.POOR -> "Poor separation"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = titleColor,
        )
        Text(
            text = "Your claps measured ${calibration.headroomDecibels.toInt()} dB " +
                "above the room.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text,
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
            color = palette.textDim,
        )
    }
}

@Composable
private fun TrialStep(
    diagnostics: ClapDiagnostics,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = Shelf.palette
    val detected = diagnostics.detectionCount > 0

    Instruction(
        title = "Try a double clap",
        body = "The new settings are running but not saved. Clap twice and see whether " +
            "it registers.",
    )

    HeroCard {
        Column(
            modifier = Modifier.fillMaxWidth().height(TRIAL_BANNER_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (detected) {
                    "Detected ${diagnostics.detectionCount}x"
                } else {
                    "Listening…"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = if (detected) palette.cyan else palette.textDim,
            )
            diagnostics.lastGapMillis?.let { gap ->
                Text(
                    "$gap ms apart",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                )
            }
        }
    }

    LevelMeter(
        level = diagnostics.level,
        background = diagnostics.noiseFloor,
        threshold = diagnostics.effectiveMinPeak,
    )
    LabelledRow("Claps accepted", diagnostics.candidateCount.toString())

    GradientButton(text = "Works — save it", onClick = onSave)
    GhostButton(text = "Back to results", onClick = onBack)
}

@Composable
private fun FailedStep(reason: String, onRetry: () -> Unit, onFinished: () -> Unit) {
    GlassCard {
        Text(
            "Calibration did not finish",
            style = MaterialTheme.typography.titleMedium,
            color = Shelf.palette.warn,
        )
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.text,
        )
    }
    GradientButton(text = "Try again", onClick = onRetry)
    LinkButton(text = "Give up", onClick = onFinished)
}

@Composable
private fun SavedStep(onFinished: () -> Unit) {
    SectionCard("Saved") {
        Text(
            "Detection now uses settings measured on this phone, in this room.",
            style = MaterialTheme.typography.bodyMedium,
            color = Shelf.palette.text,
        )
        ShelfDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "Recalibrate if you move the phone, change rooms, or the background " +
                "noise changes for good.",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textDim,
        )
    }
    GradientButton(text = "Done", onClick = onFinished)
}

private const val MILLIS_PER_SECOND = 1_000L
private val DOT_SIZE = 16.dp
private val TRIAL_BANNER_HEIGHT = 110.dp

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
