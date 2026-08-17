package com.shelfit.sentinel.data

import com.shelfit.sentinel.core.action.VibrateAction
import com.shelfit.sentinel.core.rule.AutomationRule
import com.shelfit.sentinel.core.trigger.TriggerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the automation rules.
 *
 * In-memory for now: there is exactly one seeded rule and no rule editor, so
 * persisting it would be storing a constant. When rules become user-editable they
 * outgrow DataStore Preferences and should move to their own store — the
 * [rules] flow is the seam, so the UI and the coordinator will not change.
 */
class RuleRepository {

    private val _rules = MutableStateFlow(listOf(DEFAULT_DOUBLE_CLAP_RULE))
    val rules: StateFlow<List<AutomationRule>> = _rules.asStateFlow()

    fun setEnabled(ruleId: String, enabled: Boolean) {
        _rules.update { current ->
            current.map { rule -> if (rule.id == ruleId) rule.copy(enabled = enabled) else rule }
        }
    }

    companion object {
        /**
         * The one rule that exists today.
         *
         * Its action is local feedback only — a buzz, nothing leaving the device —
         * which is enough to prove a clap travelled the whole pipeline. Outward
         * facing actions arrive in a later stage.
         */
        val DEFAULT_DOUBLE_CLAP_RULE = AutomationRule(
            id = "rule.double_clap",
            name = "Double clap",
            triggerId = TriggerId.DoubleClap,
            action = VibrateAction(),
            cooldownMillis = 1_500L,
        )
    }
}
