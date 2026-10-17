package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.DailyProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator that writes operation-owned placement deltas into daily rows: signed, underflow
 * rejected rather than zeroed, replay-safe, and scoped to the dates the operation touched.
 */
class RecoveryDailyCreditCoordinatorTest {

    private fun daoWith(vararg days: DailyProgress) = FakeDailyProgressDao(days.toList())

    @Test
    fun applyMovesMinutesBetweenDatesAndRejectsUnderflow() = runBlocking {
        val dao = daoWith(DailyProgress(date = "2026-08-12", minutesPlayed = 90, goalMinutesPlayed = 40))
        val underTest = RecoveryDailyCreditCoordinator(dao)

        val thrown = runCatching {
            underTest.apply(
                listOf(
                    DailyCreditDelta(date = "2026-08-12", minutesDelta = -120, goalMinutesDelta = -40),
                ),
            )
        }.exceptionOrNull()
        assertTrue("underflow must reject, not silently zero: $thrown", thrown != null)
        // Nothing was written.
        assertEquals(90, dao.getByDate("2026-08-12")!!.minutesPlayed)

        val applied = underTest.apply(
            listOf(
                DailyCreditDelta(date = "2026-08-12", minutesDelta = -90, goalMinutesDelta = -40),
                DailyCreditDelta(date = "2026-08-13", minutesDelta = 90, goalMinutesDelta = 40),
            ),
        )
        assertEquals(listOf("2026-08-12", "2026-08-13"), applied.map { it.date })
        assertEquals(0, dao.getByDate("2026-08-12")!!.minutesPlayed)
        assertEquals(0, dao.getByDate("2026-08-12")!!.goalMinutesPlayed)
        assertEquals(90, dao.getByDate("2026-08-13")!!.minutesPlayed)
        assertEquals(40, dao.getByDate("2026-08-13")!!.goalMinutesPlayed)
    }

    @Test
    fun goalMinutesBeyondTotalAreRejectedAsAnInvalidGraph() = runBlocking {
        val dao = daoWith(DailyProgress(date = "2026-08-12", minutesPlayed = 90, goalMinutesPlayed = 40))
        val underTest = RecoveryDailyCreditCoordinator(dao)

        val thrown = runCatching {
            underTest.apply(listOf(DailyCreditDelta(date = "2026-08-12", 0, -41)))
        }.exceptionOrNull()
        assertTrue("negative goal must reject: $thrown", thrown != null)

        val beyond = runCatching {
            underTest.apply(listOf(DailyCreditDelta(date = "2026-08-12", 0, 60)))
        }.exceptionOrNull()
        assertTrue("goal above total is an invalid graph and must reject, got $beyond", beyond != null)
        assertEquals(40, dao.getByDate("2026-08-12")!!.goalMinutesPlayed)
    }

    @Test
    fun rawShareMoveAppliesSignedDeltaAndAnUnjournaledReplayIsRejected() = runBlocking {
        val dao = daoWith(DailyProgress(date = "2026-08-12", minutesPlayed = 90, goalMinutesPlayed = 90))
        val underTest = RecoveryDailyCreditCoordinator(dao)
        val before = listOf(PlacementShare("2026-08-12", 90, true, false))
        val after = listOf(PlacementShare("2026-08-13", 90, true, false))

        val first = underTest.applyPlacementCorrections(before, after)
        assertTrue(first.isNotEmpty())
        val replay = runCatching { underTest.applyPlacementCorrections(before, after) }.exceptionOrNull()
        assertTrue("journal/revision replay guards must prevent this repeated raw delta", replay != null)
        assertEquals(0, dao.getByDate("2026-08-12")!!.minutesPlayed)
        assertEquals(90, dao.getByDate("2026-08-13")!!.minutesPlayed)
    }

    @Test
    fun unrelatedDatesAreNeverTouchedByACorrection() = runBlocking {
        val dao = daoWith(
            DailyProgress(date = "2026-08-10", minutesPlayed = 30),
            DailyProgress(date = "2026-08-12", minutesPlayed = 90),
            DailyProgress(date = "2026-08-13", minutesPlayed = 5),
        )
        val underTest = RecoveryDailyCreditCoordinator(dao)

        // The operation moves a share from the 12th to the 13th; the row's other 5 minutes on the
        // 13th (unrelated remainder) must survive untouched.
        underTest.applyPlacementCorrections(
            before = listOf(PlacementShare("2026-08-12", 90, false, false)),
            after = listOf(PlacementShare("2026-08-13", 90, false, false)),
        )

        assertEquals(30, dao.getByDate("2026-08-10")!!.minutesPlayed)
        assertEquals(0, dao.getByDate("2026-08-12")!!.minutesPlayed)
        assertEquals(95, dao.getByDate("2026-08-13")!!.minutesPlayed)
    }

    @Test
    fun sumOfTheMoveConservesTotalMinutes() = runBlocking {
        val dao = daoWith(DailyProgress(date = "2026-08-12", minutesPlayed = 90))
        val underTest = RecoveryDailyCreditCoordinator(dao)

        underTest.applyPlacementCorrections(
            before = listOf(
                PlacementShare("2026-08-12", 40, false, false),
                PlacementShare("2026-08-12", 50, false, false),
            ),
            after = listOf(PlacementShare("2026-08-13", 90, false, false)),
        )

        val total = listOf("2026-08-12", "2026-08-13").sumOf { dao.getByDate(it)!!.minutesPlayed }
        assertEquals(90, total)
    }
}
