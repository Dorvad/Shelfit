package com.shelfit.sentinel.core.rule

import com.shelfit.sentinel.core.MonotonicClock
import com.shelfit.sentinel.core.action.ActionDispatcher
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.trigger.TriggerEngine
import com.shelfit.sentinel.core.trigger.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a rule did in response to one event. Surfaced in the UI as activity history. */
data class AutomationOutcome(
    val ruleId: String,
    val ruleName: String,
    val event: TriggerEvent,
    val result: ActionResult,
)

/**
 * The rule half of the pipeline: reads [TriggerEngine.events], finds the rules that
 * match, applies cooldowns, and hands the work to [ActionDispatcher].
 *
 * This class is the whole of `TRIGGER EVENT -> RULE -> ACTION EXECUTOR`. It has no
 * reference to any sensor or any concrete action, which is the point.
 */
class AutomationCoordinator(
    private val events: Flow<TriggerEvent>,
    private val rules: Flow<List<AutomationRule>>,
    private val dispatcher: ActionDispatcher,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
) {

    private val _outcomes = MutableSharedFlow<AutomationOutcome>(replay = RECENT_OUTCOMES)

    /** Recent outcomes, replayed so the UI can show history after recomposition. */
    val outcomes: SharedFlow<AutomationOutcome> = _outcomes.asSharedFlow()

    private val lastFiredAt = mutableMapOf<String, Long>()

    private var job: Job? = null

    /** Begins observing events. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // stateIn suspends until the first rule set arrives, so an event can
            // never be evaluated against an empty rule list by accident.
            val currentRules = rules.stateIn(this)
            events.collect { event -> handle(event, currentRules.value) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handle(event: TriggerEvent, rules: List<AutomationRule>) {
        rules.filter { it.matches(event) }.forEach { rule ->
            val now = clock.elapsedMillis()
            val since = lastFiredAt[rule.id]?.let { now - it }

            val result = when {
                since != null && since < rule.cooldownMillis ->
                    ActionResult.Skipped("Cooldown: ${rule.cooldownMillis - since}ms remaining")

                rule.action == null ->
                    ActionResult.Skipped("No action configured")

                else -> {
                    lastFiredAt[rule.id] = now
                    dispatcher.dispatch(rule.action, event)
                }
            }

            _outcomes.emit(AutomationOutcome(rule.id, rule.name, event, result))
        }
    }

    private companion object {
        const val RECENT_OUTCOMES = 10
    }
}
