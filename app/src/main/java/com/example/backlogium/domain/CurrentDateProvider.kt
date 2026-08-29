package com.example.backlogium.domain

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * The current local date, re-emitted when it changes.
 *
 * A UI state builder that resolves "today" by calling [TimeProvider.today] *inside* a `combine`
 * over data flows only re-resolves it when data changes. Cross local midnight with no sync and the
 * screen keeps presenting the previous day's row as the current day's — the same user-visible
 * symptom as poll-time attribution, reached by a different route (auditfix-day-attribution
 * Decision 6). Making the date an input rather than a call means a combine re-runs on a day
 * boundary for the same reason it re-runs on a sync.
 *
 * Sleeps until the next local midnight rather than ticking: one suspended delay per day, so a
 * collector costs nothing while it waits and nothing at all once it stops. There is no alarm and no
 * wakelock — a backgrounded screen is simply not collecting — and a delay that resumes late after
 * device sleep still emits the right date, because the value is re-read from [TimeProvider] on
 * every pass rather than accumulated.
 */
@Singleton
class CurrentDateProvider @Inject constructor(private val time: TimeProvider) {

    /** The same device zone used to derive [currentDate], exposed for timestamp-to-date joins. */
    val zone: java.time.ZoneId get() = time.zone()

    val currentDate: Flow<LocalDate> = flow {
        while (true) {
            val today = time.today()
            emit(today)
            delay(millisUntilNextDay(today))
        }
    }.distinctUntilChanged()

    /**
     * Millis from now until the start of the day after [today], floored at
     * [MIN_RESCHEDULE_MILLIS]. A delay that resumes early re-reads the same date, which
     * `distinctUntilChanged` drops before it reaches a collector — the loop just waits again — so
     * an imprecise wake-up costs one arithmetic pass, never a spurious emission.
     */
    private fun millisUntilNextDay(today: LocalDate): Long {
        val nextMidnight = today.plusDays(1).atStartOfDay(time.zone()).toInstant().toEpochMilli()
        return (nextMidnight - time.nowMillis()).coerceAtLeast(MIN_RESCHEDULE_MILLIS)
    }

    private companion object {
        /** Floor on the wait, so a clock jump past the boundary cannot spin the loop. */
        const val MIN_RESCHEDULE_MILLIS = 1_000L
    }
}
