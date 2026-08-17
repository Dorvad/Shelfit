package com.shelfit.sentinel.core.action

import com.shelfit.sentinel.core.trigger.TriggerEvent
import kotlinx.coroutines.CancellationException

/**
 * Routes an [Action] to the first registered [ActionExecutor] that accepts it.
 *
 * A failing executor must not take down the automation loop, so exceptions become
 * [ActionResult.Failure]. Cancellation is rethrown, since that is the app
 * shutting the pipeline down on purpose.
 */
class ActionDispatcher(executors: List<ActionExecutor>) {

    private val executors: List<ActionExecutor> = executors.toList()

    /**
     * Whether anything registered can perform [action].
     *
     * Used at startup to assert that every action the editor offers has an executor, so
     * a missing registration is a crash on a developer's machine rather than a rule that
     * silently does nothing on a user's.
     */
    fun supports(action: Action): Boolean = executors.any { it.canExecute(action) }

    suspend fun dispatch(action: Action, event: TriggerEvent): ActionResult {
        val executor = executors.firstOrNull { it.canExecute(action) }
            ?: return ActionResult.Skipped("No executor registered for '${action.type}'")

        return try {
            executor.execute(action, event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            ActionResult.Failure(error.message ?: error::class.simpleName ?: "Unknown error")
        }
    }
}
