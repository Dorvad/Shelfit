package com.shelfit.sentinel.platform

import android.os.SystemClock
import com.shelfit.sentinel.core.MonotonicClock

/**
 * [MonotonicClock] backed by `SystemClock.elapsedRealtime()`, which keeps counting
 * in deep sleep and never jumps when the wall clock is corrected.
 */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}
