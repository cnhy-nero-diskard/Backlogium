package com.example.backlogium.data.achievement

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AchievementWatchTick(val success: Boolean, val offline: Boolean = false, val retryDelayMillis: Long = 0)

/** Serial ticks with no catch-up: the next delay starts only after the previous tick completes. */
internal suspend fun achievementWatchLoop(
    current: () -> Boolean,
    interval: suspend () -> Long,
    tick: suspend () -> AchievementWatchTick,
) {
    var failures = 0
    while (current()) {
        currentCoroutineContext().ensureActive()
        val result = tick()
        if (!current()) return
        val cadence = interval()
        val wait = when {
            result.success -> { failures = 0; cadence }
            result.offline -> cadence
            else -> maxOf(cadence, achievementRetryDelay(failures++), result.retryDelayMillis)
        }
        delay(wait)
    }
}
