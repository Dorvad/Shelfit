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
