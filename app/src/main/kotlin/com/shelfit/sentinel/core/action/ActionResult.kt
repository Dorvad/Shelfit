package com.shelfit.sentinel.core.action

/** Outcome of one attempted action. */
sealed interface ActionResult {
    data object Success : ActionResult

    /** Deliberately not run — no action configured, still in cooldown, disabled. */
    data class Skipped(val reason: String) : ActionResult

    data class Failure(val message: String) : ActionResult

    /**
     * Some of it worked.
     *
     * Exists because an action can span several devices, and "2 of 3 lamps switched" is
     * materially different from either success or failure — collapsing it into one of those
     * would either hide a problem or overstate it.
     */
    data class Partial(
        val succeeded: Int,
        val failed: Int,
        val message: String,
    ) : ActionResult
}
