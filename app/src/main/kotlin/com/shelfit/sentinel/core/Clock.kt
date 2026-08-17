package com.shelfit.sentinel.core

/**
 * Monotonic time source, injected rather than called statically so the rule and
 * engine layers stay testable on the JVM.
 *
 * Backed by `SystemClock.elapsedRealtime()` on device — it does not jump when the
 * wall clock changes, which matters for rule cooldowns on a device left running
 * for weeks.
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}
