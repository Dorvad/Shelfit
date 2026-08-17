package com.shelfit.sentinel.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfit.sentinel.AppContainer
import com.shelfit.sentinel.core.rule.AutomationRule
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
    ) { rules, currentDraft ->
        RulesUiState(
            rules = rules.map(::toRow),
            triggers = triggerOptions,
            actions = actionOptions,
            draft = currentDraft,
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
        draft.value = RuleDraft(
            id = rule.id,
            isNew = false,
            triggerId = rule.triggerId,
            actionType = rule.action?.type,
            enabled = rule.enabled,
            cooldownMillis = rule.cooldownMillis,
            minimumConfidence = rule.minimumConfidence,
        )
    }

    fun setDraftTrigger(triggerId: TriggerId) = editDraft { it.copy(triggerId = triggerId) }

    fun setDraftAction(actionType: String) = editDraft { it.copy(actionType = actionType) }

    fun setDraftEnabled(enabled: Boolean) = editDraft { it.copy(enabled = enabled) }

    fun setDraftCooldown(cooldownMillis: Long) = editDraft {
        it.copy(cooldownMillis = cooldownMillis)
    }

    fun cancelDraft() {
        draft.value = null
    }

    fun saveDraft() {
        val current = draft.value ?: return
        val action = container.actionCatalogue.action(current.actionType)
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
