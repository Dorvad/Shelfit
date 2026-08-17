package com.shelfit.sentinel.core.rule

import com.shelfit.sentinel.core.action.Action
import com.shelfit.sentinel.core.trigger.TriggerEvent
import com.shelfit.sentinel.core.trigger.TriggerId

/**
 * "When this trigger fires, do this."
 *
 * The rule is the seam in the architecture: it names a [TriggerId] and an
 * [Action] and knows nothing about either implementation. Swapping
 * `DoubleClapDetector` for a camera gesture detector only changes which
 * [triggerId] a rule points at.
 *
 * @param action null while the user has not chosen one yet — the dashboard shows
 *   this as "Not configured".
 * @param minimumConfidence detections below this are ignored, which is how a
 *   noisy detector gets tightened without changing its code.
 * @param cooldownMillis minimum gap between firings of this rule. Guards against
 *   a single physical event being reported twice.
 */
data class AutomationRule(
    val id: String,
    val name: String,
    val triggerId: TriggerId,
    val action: Action? = null,
    val enabled: Boolean = true,
    val minimumConfidence: Float = 0f,
    val cooldownMillis: Long = 0L,
) {
    /** Whether [event] should activate this rule, ignoring cooldown. */
    fun matches(event: TriggerEvent): Boolean =
        enabled && event.triggerId == triggerId && event.confidence >= minimumConfidence
}
