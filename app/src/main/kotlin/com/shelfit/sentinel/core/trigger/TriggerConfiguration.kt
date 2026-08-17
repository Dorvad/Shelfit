package com.shelfit.sentinel.core.trigger

/**
 * Per-trigger tuning supplied by the user.
 *
 * Each trigger defines its own implementation with the knobs that make sense for
 * it (an audio trigger has thresholds and time windows; a light trigger has lux
 * bounds). The engine only needs [enabled], so it takes the base type and each
 * detector narrows it to its own type.
 */
interface TriggerConfiguration {
    /** When false the engine will not start the detector at all. */
    val enabled: Boolean
}
