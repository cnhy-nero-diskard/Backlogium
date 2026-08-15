package com.example.backlogium.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The date flow that lets a UI state builder observe a day boundary without a sync
 * (auditfix-day-attribution Decision 6).
 *
 * The fake clock reads from the test scheduler's virtual time rather than being advanced by hand,
 * so the provider's own `delay` is what moves it: a test cannot accidentally assert a boundary the
 * production code would never have woken up for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentDateProviderTest {

    private val zone = ZoneId.of("Asia/Manila")

    private fun millisAt(date: LocalDate, hour: Int, minute: Int): Long =
        date.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    /** A clock anchored at [start] that then tracks [scope]'s virtual time. */
    private fun virtualClock(scope: TestScope, start: Long) = object : TimeProvider {
        override fun nowMillis(): Long = start + scope.testScheduler.currentTime
        override fun zone(): ZoneId = zone
        override fun today(): LocalDate =
            Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()
    }

    @Test
    fun `emits the current date immediately`() = runTest {
        val clock = virtualClock(this, millisAt(LocalDate.of(2026, 8, 14), 22, 30))

        val dates = CurrentDateProvider(clock).currentDate.take(1).toList()

        assertEquals(listOf(LocalDate.of(2026, 8, 14)), dates)
    }

    @Test
    fun `emits the next date once local midnight passes with no other input`() = runTest {
        val clock = virtualClock(this, millisAt(LocalDate.of(2026, 8, 14), 23, 50))

        // Taking a second value means waiting out the delay to midnight. Nothing else emits in
        // between — no sync, no settings change — which is exactly the reported scenario.
        val dates = CurrentDateProvider(clock).currentDate.take(2).toList()

        assertEquals(listOf(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 15)), dates)
    }

    @Test
    fun `waits for the boundary rather than polling`() = runTest {
        val clock = virtualClock(this, millisAt(LocalDate.of(2026, 8, 14), 23, 50))

        CurrentDateProvider(clock).currentDate.take(2).toList()

        // Exactly the ten minutes to midnight elapsed. A ticking implementation would have woken
        // repeatedly and still reported the same date, which is the cost this design avoids.
        assertEquals(10 * 60 * 1000L, testScheduler.currentTime)
    }

    @Test
    fun `emits once per boundary across several days`() = runTest {
        val clock = virtualClock(this, millisAt(LocalDate.of(2026, 8, 14), 23, 59))

        val dates = CurrentDateProvider(clock).currentDate.take(3).toList()

        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 8, 15),
                LocalDate.of(2026, 8, 16),
            ),
            dates,
        )
    }
}
