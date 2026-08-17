package com.shelfit.sentinel.platform

import com.shelfit.sentinel.core.WallClock

/** [WallClock] backed by the system clock. Display only, never for intervals. */
object SystemWallClock : WallClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
}
