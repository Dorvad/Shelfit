package com.shelfit.sentinel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.data.SentinelSettings
import com.shelfit.sentinel.trigger.audio.CalibrationQuality
import com.shelfit.sentinel.trigger.audio.SensitivityLevel
import com.shelfit.sentinel.ui.components.GhostButton
import com.shelfit.sentinel.ui.components.GradientButton
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.LinkButton
import com.shelfit.sentinel.ui.components.SectionLabel
import com.shelfit.sentinel.ui.components.SentinelScreen
import com.shelfit.sentinel.ui.components.ShelfDivider
import com.shelfit.sentinel.ui.components.ShelfSegmented
import com.shelfit.sentinel.ui.components.ShelfSwitch
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.formatDecibels
import com.shelfit.sentinel.ui.theme.SentinelTheme
import com.shelfit.sentinel.ui.theme.Shelf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsRoute(
    container: AppContainer,
    onNavigateBack: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenClapLab: () -> Unit,
    onOpenSmartHome: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container)),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = settings,
        onKeepScreenOnChange = viewModel::setKeepScreenOn,
        onDoubleClapEnabledChange = viewModel::setDoubleClapEnabled,
        onSensitivityChange = viewModel::setSensitivity,
        onHapticFeedbackChange = viewModel::setHapticFeedbackEnabled,
        onClearCalibration = viewModel::clearCalibration,
        onOpenCalibration = onOpenCalibration,
        onOpenClapLab = onOpenClapLab,
        onOpenSmartHome = onOpenSmartHome,
        onNavigateBack = onNavigateBack,
    )
}

@Composable
fun SettingsScreen(
    settings: SentinelSettings,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDoubleClapEnabledChange: (Boolean) -> Unit,
    onSensitivityChange: (SensitivityLevel) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
    onClearCalibration: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenClapLab: () -> Unit,
    onOpenSmartHome: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    SentinelScreen(title = "Settings", onNavigateBack = onNavigateBack) {
            SectionHeader("Double clap")

            SwitchRow(
                title = "Enabled",
                subtitle = "Run the double clap detector when listening starts",
                checked = settings.doubleClapEnabled,
                onCheckedChange = onDoubleClapEnabledChange,
            )

            SensitivityChooser(settings.sensitivity, onSensitivityChange)

            SwitchRow(
                title = "Vibrate on detection",
                subtitle = "Local feedback when a double clap is confirmed",
                checked = settings.hapticFeedbackEnabled,
                onCheckedChange = onHapticFeedbackChange,
            )

            ShelfDivider(Modifier.padding(vertical = 4.dp))

            SectionHeader("Calibration")

            CalibrationSummary(
                settings = settings,
                onOpenCalibration = onOpenCalibration,
                onClearCalibration = onClearCalibration,
            )

            ShelfDivider(Modifier.padding(vertical = 4.dp))

            SectionHeader("Smart home")

            Text(
                text = "Link a smart home so an automation can switch your lights and " +
                    "plugs. Nothing is linked until you connect it.",
                style = MaterialTheme.typography.bodyMedium,
                color = Shelf.palette.textDim,
            )
            GradientButton(text = "Smart home setup", onClick = onOpenSmartHome)

            ShelfDivider(Modifier.padding(vertical = 4.dp))

            SectionHeader("Device")

            SwitchRow(
                title = "Keep screen on",
                subtitle = "Useful on a wall-mounted device; costs battery on an " +
                    "unplugged one",
                checked = settings.keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
            )

            ShelfDivider(Modifier.padding(vertical = 4.dp))

            LinkButton(text = "Advanced: clap detector test", onClick = onOpenClapLab)

            Text(
                text = "Sensor data is processed on this device. Audio is never " +
                    "recorded to storage.",
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.textFaint,
            )
        }
}

/**
 * Three named steps rather than a number.
 *
 * "Normal" means whatever calibration measured, or the generic defaults if the user
 * has not calibrated — so the label stays meaningful either way.
 */
@Composable
private fun SensitivityChooser(
    selected: SensitivityLevel,
    onSelect: (SensitivityLevel) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Sensitivity", style = MaterialTheme.typography.bodyLarge, color = Shelf.palette.text)

        ShelfSegmented(
            options = SensitivityLevel.entries.map { it.label() },
            selectedIndex = SensitivityLevel.entries.indexOf(selected),
            onSelect = { onSelect(SensitivityLevel.entries[it]) },
        )

        Text(
            text = selected.explanation(),
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textFaint,
        )
        Text(
            text = "Takes effect the next time detection starts.",
            style = MaterialTheme.typography.bodySmall,
            color = Shelf.palette.textFaint,
        )
    }
}

@Composable
private fun CalibrationSummary(
    settings: SentinelSettings,
    onOpenCalibration: () -> Unit,
    onClearCalibration: () -> Unit,
) {
    val calibration = settings.calibration

    if (calibration == null) {
        SectionCard("Not calibrated") {
            Text(
                text = "Detection is using generic thresholds. Microphones and rooms " +
                    "differ enough that calibrating is worth the minute it takes.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        GradientButton(text = "Calibrate now", onClick = onOpenCalibration)
        return
    }

    SectionCard("Calibrated") {
        LabelledRow("Measured", calibration.capturedAtEpochMillis.asLocalTimestamp())
        LabelledRow("Claps used", calibration.sampleCount.toString())
        LabelledRow("Room level", formatDecibels(calibration.ambientRms))
        LabelledRow("Typical clap", formatDecibels(calibration.clapPeakMedian))
        LabelledRow(
            label = "Headroom",
            value = "${calibration.headroomDecibels.toInt()} dB",
            emphasise = calibration.quality == CalibrationQuality.POOR,
        )
        LabelledRow(
            label = "Quality",
            value = when (calibration.quality) {
                CalibrationQuality.GOOD -> "Good"
                CalibrationQuality.MARGINAL -> "Tight"
                CalibrationQuality.POOR -> "Poor — recalibrate"
            },
            emphasise = calibration.quality != CalibrationQuality.GOOD,
        )
    }
    GhostButton(text = "Calibrate again", onClick = onOpenCalibration)
    GhostButton(text = "Use generic settings", onClick = onClearCalibration)
}

private fun SensitivityLevel.label(): String = when (this) {
    SensitivityLevel.LOW -> "Low"
    SensitivityLevel.NORMAL -> "Normal"
    SensitivityLevel.HIGH -> "High"
}

private fun SensitivityLevel.explanation(): String = when (this) {
    SensitivityLevel.LOW ->
        "Only clear claps close to the phone. Fewest false triggers."

    SensitivityLevel.NORMAL ->
        "Balanced. Uses your calibrated levels if you have calibrated."

    SensitivityLevel.HIGH ->
        "Picks up quieter and more distant claps. Expect more false triggers."
}

private fun Long.asLocalTimestamp(): String =
    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

@Composable
private fun SectionHeader(title: String) {
    SectionLabel(title, Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Shelf.palette.text)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Shelf.palette.textDim,
            )
        }
        ShelfSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    SentinelTheme {
        SettingsScreen(
            settings = SentinelSettings(),
            onKeepScreenOnChange = {},
            onDoubleClapEnabledChange = {},
            onSensitivityChange = {},
            onHapticFeedbackChange = {},
            onClearCalibration = {},
            onOpenCalibration = {},
            onOpenClapLab = {},
            onOpenSmartHome = {},
            onNavigateBack = {},
        )
    }
}
