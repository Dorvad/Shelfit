package com.shelfit.sentinel.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.action.SmartHomeDeviceAction
import com.shelfit.sentinel.core.action.SmartHomeTarget
import com.shelfit.sentinel.core.rule.AutomationRule
import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeDeviceList
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.describe
import com.shelfit.sentinel.core.trigger.TriggerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One rule as the list shows it. */
data class RuleRowUi(
    val id: String,
    val enabled: Boolean,
    val triggerName: String,
    /** False when this build has no detector for the rule's trigger. */
    val triggerAvailable: Boolean,
    val actionName: String?,
    val cooldownMillis: Long,
)

/** A rule being edited. Nothing is written until it is saved. */
data class RuleDraft(
    val id: String,
    val isNew: Boolean,
    val triggerId: TriggerId,
    val actionType: String?,
    val enabled: Boolean,
    val cooldownMillis: Long,
    /** Not editable here, but carried so editing a rule does not discard it. */
    val minimumConfidence: Float,
    /**
     * Devices the smart-home action will switch, in the order the user picked them.
     *
     * Held here rather than rebuilt from the live device list, so a device that has gone
     * offline — or vanished from the home — stays in the rule the user saved instead of
     * being silently dropped by an edit.
     */
    val targets: List<SmartHomeTarget> = emptyList(),
    val command: SmartHomeCommand = SmartHomeCommand.TOGGLE,
) {
    val isSmartHome: Boolean get() = actionType == SmartHomeDeviceAction.TYPE

    /** Whether saving would produce a rule that actually does something. */
    val complete: Boolean get() = !isSmartHome || targets.isNotEmpty()
}

/** A device offered in the rule editor, with whether it is currently pickable. */
data class DeviceChoiceUi(
    val id: DeviceId,
    val name: String,
    val detail: String,
    val chosen: Boolean,
    /** Offline devices stay pickable — they are usually back by the time a rule fires. */
    val warning: String?,
    val stateKnown: Boolean,
)

/** Everything the editor needs to configure a smart-home action. */
data class SmartHomeEditorUi(
    val connected: Boolean = false,
    val statusMessage: String? = null,
    val loading: Boolean = false,
    val devices: List<DeviceChoiceUi> = emptyList(),
    /**
     * False when any chosen device does not report whether it is on, which is the one case
     * where a toggle cannot be carried out.
     */
    val toggleAvailable: Boolean = true,
)

data class TriggerOption(
    val id: TriggerId,
    val name: String,
    val description: String,
)

data class ActionOption(
    val type: String,
    val name: String,
    val description: String,
)

data class RulesUiState(
    val rules: List<RuleRowUi> = emptyList(),
    /** Only triggers this build can actually detect. Never a placeholder. */
    val triggers: List<TriggerOption> = emptyList(),
    val actions: List<ActionOption> = emptyList(),
    val draft: RuleDraft? = null,
    val smartHome: SmartHomeEditorUi = SmartHomeEditorUi(),
)

/**
 * State for the rule editor.
 *
 * The options offered come from the trigger registry and the action catalogue, so the
 * editor can only ever offer things that exist: a trigger with a detector, and an action
 * with an executor. That is why there are no placeholder entries for future sensors.
 */
class RulesViewModel(private val container: AppContainer) : ViewModel() {

    private val draft = MutableStateFlow<RuleDraft?>(null)

    private val triggerOptions: List<TriggerOption> = container.triggerRegistry.triggers.map {
        TriggerOption(id = it.id, name = it.displayName, description = it.description)
    }

    private val actionOptions: List<ActionOption> = container.actionCatalogue.kinds.map {
        ActionOption(type = it.type, name = it.displayName, description = it.description)
    }

    val uiState: StateFlow<RulesUiState> = combine(
        container.ruleRepository.rules,
        draft,
        container.smartHomeClient.state,
        container.smartHomeDirectory.devices,
    ) { rules, currentDraft, connection, deviceList ->
        RulesUiState(
            rules = rules.map(::toRow),
            triggers = triggerOptions,
            actions = actionOptions,
            draft = currentDraft,
            smartHome = smartHomeEditor(currentDraft, connection, deviceList),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = RulesUiState(triggers = triggerOptions, actions = actionOptions),
    )

    fun startNewRule() {
        val trigger = triggerOptions.firstOrNull() ?: return
        draft.value = RuleDraft(
            id = container.ruleRepository.newRuleId(),
            isNew = true,
            triggerId = trigger.id,
            actionType = actionOptions.firstOrNull()?.type,
            enabled = true,
            cooldownMillis = DEFAULT_COOLDOWN_MILLIS,
            minimumConfidence = 0f,
        )
    }

    fun editRule(ruleId: String) {
        val rule = uiStateRule(ruleId) ?: return
        val smartHome = rule.action as? SmartHomeDeviceAction
        draft.value = RuleDraft(
            id = rule.id,
            isNew = false,
            triggerId = rule.triggerId,
            actionType = rule.action?.type,
            enabled = rule.enabled,
            cooldownMillis = rule.cooldownMillis,
            minimumConfidence = rule.minimumConfidence,
            targets = smartHome?.targets ?: emptyList(),
            command = smartHome?.command ?: SmartHomeCommand.TOGGLE,
        )
        // Editing a smart-home rule is the moment the device list matters, and it may be
        // stale or never loaded. Refreshing here costs one call and avoids an empty picker.
        if (smartHome != null) container.smartHomeDirectory.reload()
    }

    fun setDraftTrigger(triggerId: TriggerId) = editDraft { it.copy(triggerId = triggerId) }

    fun setDraftAction(actionType: String) = editDraft { current ->
        if (actionType == SmartHomeDeviceAction.TYPE) container.smartHomeDirectory.reload()
        current.copy(actionType = actionType)
    }

    /** Adds or removes a device. The name is captured now so the rule can be read later. */
    fun toggleDraftDevice(deviceId: DeviceId, name: String) = editDraft { current ->
        val existing = current.targets.firstOrNull { it.deviceId == deviceId }
        val targets = if (existing != null) {
            current.targets - existing
        } else {
            current.targets + SmartHomeTarget(deviceId, name)
        }
        // Dropping to a device with no readable state makes Toggle impossible, so the
        // command falls back rather than being left in a state that cannot run.
        val command = if (current.command == SmartHomeCommand.TOGGLE && !toggleable(targets)) {
            SmartHomeCommand.ON
        } else {
            current.command
        }
        current.copy(targets = targets, command = command)
    }

    fun setDraftCommand(command: SmartHomeCommand) = editDraft { it.copy(command = command) }

    fun setDraftEnabled(enabled: Boolean) = editDraft { it.copy(enabled = enabled) }

    fun setDraftCooldown(cooldownMillis: Long) = editDraft {
        it.copy(cooldownMillis = cooldownMillis)
    }

    fun cancelDraft() {
        draft.value = null
    }

    fun saveDraft() {
        val current = draft.value ?: return
        if (!current.complete) return
        val action = container.actionCatalogue.action(current.actionType, current.parameters())
        val triggerName = triggerOptions.firstOrNull { it.id == current.triggerId }?.name
            ?: current.triggerId.value

        viewModelScope.launch {
            container.ruleRepository.upsert(
                AutomationRule(
                    id = current.id,
                    // Derived rather than typed. A rule reads as what it does, and there
                    // is no free text to keep meaningful as the rule changes.
                    name = "$triggerName → ${action?.displayName ?: "No action"}",
                    triggerId = current.triggerId,
                    action = action,
                    enabled = current.enabled,
                    minimumConfidence = current.minimumConfidence,
                    cooldownMillis = current.cooldownMillis,
                ),
            )
            draft.value = null
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            container.ruleRepository.delete(ruleId)
            if (draft.value?.id == ruleId) draft.value = null
        }
    }

    fun setEnabled(ruleId: String, enabled: Boolean) {
        viewModelScope.launch { container.ruleRepository.setEnabled(ruleId, enabled) }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            container.ruleRepository.resetToDefaults()
            draft.value = null
        }
    }

    private fun editDraft(transform: (RuleDraft) -> RuleDraft) {
        draft.value = draft.value?.let(transform)
    }

    /**
     * The draft as the parameters an action rebuilds itself from.
     *
     * Routed through the action rather than assembled here, so the editor never has to know
     * the parameter keys — the action owns both halves of its own encoding.
     */
    private fun RuleDraft.parameters(): Map<String, String> =
        if (isSmartHome) {
            SmartHomeDeviceAction(targets = targets, command = command).parameters
        } else {
            emptyMap()
        }

    /**
     * Whether every chosen device reports its state.
     *
     * A device the app cannot see is treated as toggleable: it may be back by the time the
     * rule fires, and the executor will report honestly if it is not. Refusing to offer
     * Toggle because a lamp is temporarily offline would be the wrong trade.
     */
    private fun toggleable(targets: List<SmartHomeTarget>): Boolean {
        val known = container.smartHomeDirectory.devices.value.devices.associateBy { it.id }
        return targets.all { target -> known[target.deviceId]?.stateKnown ?: true }
    }

    private fun smartHomeEditor(
        draft: RuleDraft?,
        connection: SmartHomeState,
        deviceList: SmartHomeDeviceList,
    ): SmartHomeEditorUi {
        if (draft?.isSmartHome != true) return SmartHomeEditorUi()

        val chosen = draft.targets.map { it.deviceId }.toSet()
        val devices = deviceList.selectable.map { device ->
            DeviceChoiceUi(
                id = device.id,
                name = device.name,
                detail = listOfNotNull(device.roomName, device.kind.name.lowercase())
                    .joinToString(" · "),
                chosen = device.id in chosen,
                warning = when {
                    !device.reachable -> "Offline right now"
                    !device.stateKnown -> "Does not report whether it is on"
                    else -> null
                },
                stateKnown = device.stateKnown,
            )
        }

        // Devices saved in the rule that the current home no longer lists: shown so the user
        // can see what an automation still points at rather than finding out when it fails.
        val missing = draft.targets.filter { target ->
            deviceList.loaded && deviceList.devices.none { it.id == target.deviceId }
        }

        return SmartHomeEditorUi(
            connected = connection.isConnected,
            statusMessage = statusMessage(connection, deviceList, missing),
            loading = deviceList.loading,
            devices = devices,
            toggleAvailable = toggleable(draft.targets),
        )
    }

    private fun statusMessage(
        connection: SmartHomeState,
        deviceList: SmartHomeDeviceList,
        missing: List<SmartHomeTarget>,
    ): String? = when {
        connection is SmartHomeState.NotConfigured ->
            "This build cannot reach a smart home yet. You can still build the automation, " +
                "and it will run once a home is connected."

        connection is SmartHomeState.NotConnected ->
            "Connect a smart home in Settings to choose devices."

        connection is SmartHomeState.PermissionRequired ->
            "Access was withdrawn. Reconnect in Settings to choose devices."

        connection is SmartHomeState.Unavailable ->
            "Cannot reach your home: ${connection.failure.kind.describe()}."

        deviceList.failure != null ->
            "Could not load devices: ${deviceList.failure.kind.describe()}."

        missing.isNotEmpty() -> missing.joinToString(
            prefix = "No longer in this home: ",
            postfix = ". The automation will report them as missing.",
        ) { it.name }

        else -> null
    }

    /** The stored rule behind a row, read from the flow's latest value. */
    private fun uiStateRule(ruleId: String): AutomationRule? =
        storedRules.firstOrNull { it.id == ruleId }

    private var storedRules: List<AutomationRule> = emptyList()

    init {
        viewModelScope.launch {
            container.ruleRepository.rules.collect { storedRules = it }
        }
    }

    private fun toRow(rule: AutomationRule) = RuleRowUi(
        id = rule.id,
        enabled = rule.enabled,
        triggerName = triggerOptions.firstOrNull { it.id == rule.triggerId }?.name
            ?: rule.triggerId.value,
        triggerAvailable = triggerOptions.any { it.id == rule.triggerId },
        actionName = rule.action?.displayName,
        cooldownMillis = rule.cooldownMillis,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val DEFAULT_COOLDOWN_MILLIS = 1_500L

        /** Offered in the editor. Enough spread to be useful without being a text field. */
        val CooldownOptions = listOf(500L, 1_500L, 3_000L, 10_000L)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RulesViewModel(container) }
        }
    }
}
