package com.shelfit.sentinel.core.action

/**
 * Something to do when a rule fires. Pure data — an [ActionExecutor] performs it.
 *
 * Splitting the description from the execution is what will let a
 * `GoogleHomeAction` (or a webhook, or a local notification) be added without the
 * rule layer learning anything about smart-home APIs.
 */
interface Action {
    /** Stable type key, used for persistence and for executor matching. */
    val type: String

    /** Shown in the UI. */
    val displayName: String
}

/**
 * Writes the event to logcat. Useful while the real actions do not exist yet, and
 * worth keeping afterwards as a diagnostic.
 */
data object LogAction : Action {
    override val type: String = "log"
    override val displayName: String = "Write to log"
}
