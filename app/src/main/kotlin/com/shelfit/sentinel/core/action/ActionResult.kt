package com.shelfit.sentinel.core.action

/** Outcome of one attempted action. */
sealed interface ActionResult {
    data object Success : ActionResult

    /** Deliberately not run — no action configured, still in cooldown, disabled. */
    data class Skipped(val reason: String) : ActionResult

    data class Failure(val message: String) : ActionResult
}
