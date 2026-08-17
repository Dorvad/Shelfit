package com.shelfit.sentinel.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.R
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.trigger.TriggerId
import com.shelfit.sentinel.ui.components.SectionCard
import com.shelfit.sentinel.ui.theme.SentinelTheme

@Composable
fun RulesRoute(
    container: AppContainer,
    onNavigateBack: () -> Unit,
    viewModel: RulesViewModel = viewModel(factory = RulesViewModel.factory(container)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RulesScreen(
        uiState = uiState,
        onAddRule = viewModel::startNewRule,
        onEditRule = viewModel::editRule,
        onDeleteRule = viewModel::deleteRule,
        onSetEnabled = viewModel::setEnabled,
        onResetToDefaults = viewModel::resetToDefaults,
        onDraftTrigger = viewModel::setDraftTrigger,
        onDraftAction = viewModel::setDraftAction,
        onDraftEnabled = viewModel::setDraftEnabled,
        onDraftCooldown = viewModel::setDraftCooldown,
        onToggleDevice = viewModel::toggleDraftDevice,
        onDraftCommand = viewModel::setDraftCommand,
        onSaveDraft = viewModel::saveDraft,
        onCancelDraft = viewModel::cancelDraft,
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    uiState: RulesUiState,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onDeleteRule: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onDraftTrigger: (TriggerId) -> Unit,
    onDraftAction: (String) -> Unit,
    onDraftEnabled: (Boolean) -> Unit,
    onDraftCooldown: (Long) -> Unit,
    onToggleDevice: (DeviceId, String) -> Unit,
    onDraftCommand: (SmartHomeCommand) -> Unit,
    onSaveDraft: () -> Unit,
    onCancelDraft: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val draft = uiState.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            draft == null -> "Automations"
                            draft.isNew -> "New automation"
                            else -> "Edit automation"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (draft == null) onNavigateBack else onCancelDraft) {
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
            if (draft == null) {
                RuleList(
                    uiState = uiState,
                    onAddRule = onAddRule,
                    onEditRule = onEditRule,
                    onSetEnabled = onSetEnabled,
                    onResetToDefaults = onResetToDefaults,
                )
            } else {
                RuleEditor(
                    draft = draft,
                    triggers = uiState.triggers,
                    actions = uiState.actions,
                    smartHome = uiState.smartHome,
                    onDraftTrigger = onDraftTrigger,
                    onDraftAction = onDraftAction,
                    onDraftEnabled = onDraftEnabled,
                    onDraftCooldown = onDraftCooldown,
                    onToggleDevice = onToggleDevice,
                    onDraftCommand = onDraftCommand,
                    onSave = onSaveDraft,
                    onCancel = onCancelDraft,
                    onDelete = { onDeleteRule(draft.id) },
                )
            }
        }
    }
}

@Composable
private fun RuleList(
    uiState: RulesUiState,
    onAddRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
) {
    Text(
        text = "A trigger on its own does nothing. An automation says what should happen " +
            "when one fires.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (uiState.rules.isEmpty()) {
        SectionCard("No automations") {
            Text(
                text = "Nothing will happen when a clap is detected. Add an automation to " +
                    "change that.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        uiState.rules.forEach { rule ->
            RuleCard(
                rule = rule,
                onEdit = { onEditRule(rule.id) },
                onSetEnabled = { onSetEnabled(rule.id, it) },
            )
        }
    }

    Button(onClick = onAddRule, modifier = Modifier.fillMaxWidth()) {
        Text("Add automation")
    }
    TextButton(onClick = onResetToDefaults, modifier = Modifier.fillMaxWidth()) {
        Text("Reset to defaults")
    }

    Text(
        text = "Local actions run entirely on this phone. Smart-home actions need a home " +
            "connected in Settings first — you can build the automation either way, and " +
            "it will start working once the home is linked.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Reads as the rule does: WHEN this, DO that. */
@Composable
private fun RuleCard(
    rule: RuleRowUi,
    onEdit: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "WHEN",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = rule.triggerName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "DO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = rule.actionName ?: "Nothing — no action set",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (rule.actionName == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (!rule.triggerAvailable) {
                    Text(
                        text = "This version cannot detect that trigger yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onSetEnabled,
                enabled = rule.triggerAvailable,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(
    draft: RuleDraft,
    triggers: List<TriggerOption>,
    actions: List<ActionOption>,
    smartHome: SmartHomeEditorUi,
    onDraftTrigger: (TriggerId) -> Unit,
    onDraftAction: (String) -> Unit,
    onDraftEnabled: (Boolean) -> Unit,
    onDraftCooldown: (Long) -> Unit,
    onToggleDevice: (DeviceId, String) -> Unit,
    onDraftCommand: (SmartHomeCommand) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard("When") {
        triggers.forEach { trigger ->
            ChoiceRow(
                title = trigger.name,
                subtitle = trigger.description,
                selected = trigger.id == draft.triggerId,
                onSelect = { onDraftTrigger(trigger.id) },
            )
        }
        if (triggers.none { it.id == draft.triggerId }) {
            Text(
                text = "This automation uses a trigger this version cannot detect. " +
                    "Choose one above to make it work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "Double clap is the only trigger so far. Camera, light and movement " +
                "triggers are later stages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("Do") {
        actions.forEach { action ->
            ChoiceRow(
                title = action.name,
                subtitle = action.description,
                selected = action.type == draft.actionType,
                onSelect = { onDraftAction(action.type) },
            )
        }
    }

    if (draft.isSmartHome) {
        SmartHomeActionEditor(
            draft = draft,
            smartHome = smartHome,
            onToggleDevice = onToggleDevice,
            onCommand = onDraftCommand,
        )
    }

    SectionCard("Options") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "A disabled automation stays in the list and does nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.enabled, onCheckedChange = onDraftEnabled)
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("Minimum gap between runs", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            RulesViewModel.CooldownOptions.forEachIndexed { index, millis ->
                SegmentedButton(
                    selected = millis == draft.cooldownMillis,
                    onClick = { onDraftCooldown(millis) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = RulesViewModel.CooldownOptions.size,
                    ),
                    label = { Text(millis.asSeconds()) },
                )
            }
        }
        Text(
            text = "Stops one gesture running the action twice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Button(
        onClick = onSave,
        // An incomplete smart-home action would save a rule that fires and does nothing.
        enabled = draft.complete,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (draft.isNew) "Add automation" else "Save changes")
    }
    if (!draft.complete) {
        Text(
            text = "Choose at least one device to save this automation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
    if (!draft.isNew) {
        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text("Delete automation")
        }
    }
}

/**
 * Devices and the command to send them.
 *
 * The device list is the live one; the chosen set comes from the draft. That difference is
 * deliberate — a device that has left the home stays chosen and is called out, rather than
 * disappearing from a rule the user thought was configured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartHomeActionEditor(
    draft: RuleDraft,
    smartHome: SmartHomeEditorUi,
    onToggleDevice: (DeviceId, String) -> Unit,
    onCommand: (SmartHomeCommand) -> Unit,
) {
    SectionCard("Devices") {
        smartHome.statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            smartHome.loading -> Text(
                text = "Loading devices…",
                style = MaterialTheme.typography.bodyMedium,
            )

            smartHome.devices.isEmpty() && smartHome.connected -> Text(
                text = "No lights or smart plugs were found in this home.",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> smartHome.devices.forEach { device ->
                DeviceChoiceRow(
                    device = device,
                    onToggle = { onToggleDevice(device.id, device.name) },
                )
            }
        }

        // Chosen devices the current list does not contain — an offline home, or a device
        // that has been removed. Shown as checked so the rule reads as what it actually is.
        val listed = smartHome.devices.map { it.id }.toSet()
        draft.targets.filterNot { it.deviceId in listed }.forEach { target ->
            DeviceChoiceRow(
                device = DeviceChoiceUi(
                    id = target.deviceId,
                    name = target.name,
                    detail = "Not in the list right now",
                    chosen = true,
                    warning = "Cannot be checked at the moment",
                    stateKnown = false,
                ),
                onToggle = { onToggleDevice(target.deviceId, target.name) },
            )
        }
    }

    SectionCard("What to do with them") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SmartHomeCommand.entries.forEachIndexed { index, command ->
                SegmentedButton(
                    selected = command == draft.command,
                    onClick = { onCommand(command) },
                    // Toggle needs to know the current state of every chosen device.
                    enabled = command != SmartHomeCommand.TOGGLE || smartHome.toggleAvailable,
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SmartHomeCommand.entries.size,
                    ),
                    label = { Text(command.label) },
                )
            }
        }
        Text(
            text = if (smartHome.toggleAvailable) {
                "Toggle switches each device to the opposite of what it is now."
            } else {
                "Toggle is unavailable: one of the chosen devices does not report whether " +
                    "it is on, and guessing could switch it the wrong way."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceChoiceRow(device: DeviceChoiceUi, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = device.chosen, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = device.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            device.warning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Long.asSeconds(): String =
    if (this % 1_000L == 0L) "${this / 1_000L}s" else "%.1fs".format(this / 1_000f)

@Preview(showBackground = true)
@Composable
private fun RulesScreenPreview() {
    SentinelTheme {
        RulesScreen(
            uiState = RulesUiState(
                rules = listOf(
                    RuleRowUi(
                        id = "rule.1",
                        enabled = true,
                        triggerName = "Double clap",
                        triggerAvailable = true,
                        actionName = "Vibrate the phone",
                        cooldownMillis = 1_500L,
                    ),
                    RuleRowUi(
                        id = "rule.2",
                        enabled = false,
                        triggerName = "Double clap",
                        triggerAvailable = true,
                        actionName = "Show a notification",
                        cooldownMillis = 3_000L,
                    ),
                ),
                triggers = listOf(
                    TriggerOption(TriggerId.DoubleClap, "Double clap", "Clap twice, quickly"),
                ),
                actions = listOf(
                    ActionOption("debug.vibrate", "Vibrate the phone", "A short buzz."),
                ),
            ),
            onAddRule = {},
            onEditRule = {},
            onDeleteRule = {},
            onSetEnabled = { _, _ -> },
            onResetToDefaults = {},
            onDraftTrigger = {},
            onDraftAction = {},
            onDraftEnabled = {},
            onDraftCooldown = {},
            onToggleDevice = { _, _ -> },
            onDraftCommand = {},
            onSaveDraft = {},
            onCancelDraft = {},
            onNavigateBack = {},
        )
    }
}
