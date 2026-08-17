package com.shelfit.sentinel.platform

import android.util.Log
import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.action.ActionExecutor
import com.shelfit.sentinel.core.action.ActionResult
import com.shelfit.sentinel.core.action.DebugLogAction
import com.shelfit.sentinel.core.trigger.TriggerEvent

/**
 * Executes [DebugLogAction]. Serves as the reference implementation of
 * [ActionExecutor] and as a way to confirm the pipeline end to end before any
 * outward-facing action exists.
 *
 * Logs the trigger identity and confidence only — never raw sensor detail.
 */
class LogActionExecutor : ActionExecutor {

    override fun canExecute(action: Action): Boolean = action is DebugLogAction

    override suspend fun execute(action: Action, event: TriggerEvent): ActionResult {
        Log.i(TAG, "${event.triggerId} fired (confidence=${event.confidence})")
        return ActionResult.Success
    }

    private companion object {
        const val TAG = "SentinelAction"
    }
}
