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

/**
 * Wall-clock time, for anything a person reads.
 *
 * Separate from [MonotonicClock] on purpose: this one is allowed to jump when the
 * clock is corrected or the timezone changes, so it must never be used for
 * measuring an interval. Its only job is turning an event into "18:43:12".
 */
fun interface WallClock {
    fun epochMillis(): Long
}
