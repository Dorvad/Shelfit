package com.shelfit.sentinel.core.action

/**
 * Something to do when a rule fires. Pure data — an [ActionExecutor] performs it.
 *
 * Splitting the description from the execution is what lets a smart-home action be
 * added later without the rule layer, or any detector, learning anything about
 * smart-home APIs. A detector emits a [com.shelfit.sentinel.core.trigger.TriggerEvent];
 * it has no reference to this file and must never acquire one.
 */
interface Action {
    /** Stable type key. Persisted in rules, and how an executor recognises its own work. */
    val type: String

    /** Shown in the UI. */
    val displayName: String
}

/**
 * Writes the detection to logcat.
 *
 * The cheapest possible proof that an event reached the action layer, and worth keeping
 * once real actions exist.
 */
data object DebugLogAction : Action {
    override val type: String = "debug.log"
    override val displayName: String = "Write to log"
}

/**
 * Buzzes the device.
 *
 * @param durationMillis not user-editable yet; see [ActionKind] for where per-rule
 *   parameters will go when an action needs them.
 */
data class VibrateAction(
    val durationMillis: Long = 140L,
) : Action {
    override val type: String = "debug.vibrate"
    override val displayName: String = "Vibrate the phone"
}

/**
 * Posts a notification naming the trigger that fired.
 *
 * Local feedback, like the other two — nothing leaves the device. Useful when the phone
 * is across the room and a vibration would go unnoticed.
 */
data object ShowNotificationAction : Action {
    override val type: String = "debug.notification"
    override val displayName: String = "Show a notification"
}
