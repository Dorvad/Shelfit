package com.shelfit.sentinel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.data.SentinelSettings
import com.shelfit.sentinel.trigger.audio.CalibrationQuality
import com.shelfit.sentinel.trigger.audio.SensitivityLevel
import com.shelfit.sentinel.ui.components.LabelledRow
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.components.formatDecibels
import com.shelfit.sentinel.ui.theme.SentinelTheme
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

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Calibration")

            CalibrationSummary(
                settings = settings,
                onOpenCalibration = onOpenCalibration,
                onClearCalibration = onClearCalibration,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Smart home")

            Text(
                text = "Link a smart home so an automation can switch your lights and " +
                    "plugs. Nothing is linked until you connect it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSmartHome, modifier = Modifier.fillMaxWidth()) {
                Text("Smart home setup")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Device")

            SwitchRow(
                title = "Keep screen on",
                subtitle = "Useful on a wall-mounted device; costs battery on an " +
                    "unplugged one",
                checked = settings.keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            TextButton(onClick = onOpenClapLab, modifier = Modifier.fillMaxWidth()) {
                Text("Advanced: clap detector test")
            }

            Text(
                text = "Sensor data is processed on this device. Audio is never " +
                    "recorded to storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Three named steps rather than a number.
 *
 * "Normal" means whatever calibration measured, or the generic defaults if the user
 * has not calibrated — so the label stays meaningful either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensitivityChooser(
    selected: SensitivityLevel,
    onSelect: (SensitivityLevel) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Sensitivity", style = MaterialTheme.typography.bodyLarge)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SensitivityLevel.entries.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = level == selected,
                    onClick = { onSelect(level) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SensitivityLevel.entries.size,
                    ),
                    label = { Text(level.label()) },
                )
            }
        }

        Text(
            text = selected.explanation(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Takes effect the next time detection starts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Button(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth()) {
            Text("Calibrate now")
        }
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
    Button(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth()) {
        Text("Calibrate again")
    }
    OutlinedButton(onClick = onClearCalibration, modifier = Modifier.fillMaxWidth()) {
        Text("Use generic settings")
    }
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
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
