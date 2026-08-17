package com.shelfit.sentinel.core.action

import com.shelfit.sentinel.core.trigger.TriggerEvent

/**
 * Performs one family of [Action]s.
 *
 * New capabilities arrive as new executors registered in `AppContainer`; the rule
 * layer keeps dispatching through [ActionDispatcher] unchanged.
 */
interface ActionExecutor {
    /** True if this executor handles the given action. */
    fun canExecute(action: Action): Boolean

    /**
     * Runs [action]. Called from a background dispatcher, so implementations may
     * block on I/O, but they must remain cancellable.
     *
     * @param event the detection that caused this, for context and logging.
     */
    suspend fun execute(action: Action, event: TriggerEvent): ActionResult
}
