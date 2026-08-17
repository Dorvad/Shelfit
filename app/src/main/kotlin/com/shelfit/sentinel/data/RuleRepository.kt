package com.shelfit.sentinel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shelfit.sentinel.core.action.ActionCatalogue
import com.shelfit.sentinel.core.action.VibrateAction
import com.shelfit.sentinel.core.rule.AutomationRule
import com.shelfit.sentinel.core.trigger.TriggerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.rulesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "automation_rules",
)

/**
 * Stores the user's automation rules.
 *
 * The [rules] flow is the only thing the automation layer sees, which is why the move
 * from an in-memory constant to persisted, user-editable rules changed nothing in
 * `AutomationCoordinator`.
 *
 * @param idFactory injected so tests get predictable rule ids.
 */
class RuleRepository(
    context: Context,
    private val catalogue: ActionCatalogue,
    private val idFactory: () -> String = { "rule." + java.util.UUID.randomUUID() },
) {

    private val dataStore = context.rulesDataStore

    val rules: Flow<List<AutomationRule>> = dataStore.data
        .catch { error ->
            // A corrupt file must not stop the app starting. Falling back to defaults is
            // the least surprising outcome: the app keeps working and the user can see
            // and fix the rules.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            RuleCodec.decode(preferences[Keys.Rules], catalogue) ?: defaultRules()
        }

    fun newRuleId(): String = idFactory()

    /** Adds a rule, or replaces the one with the same id. */
    suspend fun upsert(rule: AutomationRule) = update { current ->
        val index = current.indexOfFirst { it.id == rule.id }
        if (index >= 0) current.toMutableList().also { it[index] = rule } else current + rule
    }

    suspend fun delete(ruleId: String) = update { current ->
        current.filterNot { it.id == ruleId }
    }

    suspend fun setEnabled(ruleId: String, enabled: Boolean) = update { current ->
        current.map { rule -> if (rule.id == ruleId) rule.copy(enabled = enabled) else rule }
    }

    /** Puts back the rule a fresh install starts with. */
    suspend fun resetToDefaults() = update { defaultRules() }

    /**
     * Read, modify, write in one transaction.
     *
     * Reading inside `edit` rather than from [rules] is what makes concurrent edits safe:
     * two screens toggling different rules at once cannot overwrite each other.
     */
    private suspend fun update(transform: (List<AutomationRule>) -> List<AutomationRule>) {
        dataStore.edit { preferences ->
            val current = RuleCodec.decode(preferences[Keys.Rules], catalogue) ?: defaultRules()
            preferences[Keys.Rules] = RuleCodec.encode(transform(current))
        }
    }

    private fun defaultRules(): List<AutomationRule> = listOf(DEFAULT_DOUBLE_CLAP_RULE)

    private object Keys {
        val Rules = stringPreferencesKey("rules")
    }

    companion object {
        /**
         * What a fresh install starts with: a double clap buzzes the phone.
         *
         * Local feedback only, and enough to prove a clap travelled the whole pipeline
         * before the user has configured anything.
         */
        val DEFAULT_DOUBLE_CLAP_RULE = AutomationRule(
            id = "rule.default_double_clap",
            name = "Double clap → Vibrate the phone",
            triggerId = TriggerId.DoubleClap,
            action = VibrateAction(),
            cooldownMillis = 1_500L,
        )
    }
}
