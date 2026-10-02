package com.example.backlogium.work.setup

import android.os.SystemClock

/**
 * Monotonic elapsed-time source for the foreground observation budget.
 *
 * The budget is measured monotonically (never wall clock, so a timezone or clock change cannot
 * stretch a foreground wait) and is injectable so tests can drive the 120-second expiry with
 * virtual time instead of sleeping.
 */
fun interface MonotonicClock {
    fun elapsedMonotonicMillis(): Long
}

/** Production [MonotonicClock] over [SystemClock.elapsedRealtime]. */
class SystemMonotonicClock : MonotonicClock {
    override fun elapsedMonotonicMillis(): Long = SystemClock.elapsedRealtime()
}