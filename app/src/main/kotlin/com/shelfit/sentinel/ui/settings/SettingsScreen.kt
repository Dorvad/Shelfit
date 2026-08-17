package com.shelfit.sentinel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.shelfit.sentinel.ui.theme.SentinelTheme

@Composable
fun SettingsRoute(
    container: AppContainer,
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container)),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = settings,
        onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
        onDoubleClapEnabledChange = { viewModel.setDoubleClapEnabled(it) },
        onDoubleClapSensitivityChange = { viewModel.setDoubleClapSensitivity(it) },
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SentinelSettings,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDoubleClapEnabledChange: (Boolean) -> Unit,
    onDoubleClapSensitivityChange: (Float) -> Unit,
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

            Column {
                Text("Sensitivity", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Higher values react to quieter claps and cause more " +
                        "false positives.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.doubleClapSensitivity,
                    onValueChange = onDoubleClapSensitivityChange,
                    valueRange = 0f..1f,
                )
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

            Text(
                text = "Sensor data is processed on this device. Audio is never " +
                    "recorded to storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
            onDoubleClapSensitivityChange = {},
            onNavigateBack = {},
        )
    }
}
