package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresencePlaytimeRefilingRuleTest {
    @Test
    fun selectedRangeClipsSessionPlacementWithoutOverlapOrMinuteChanges() {
        val original = session(appId = GAME, startAt = 0L, endAt = 100 * MINUTE, minutes = 10)
        val selection = CloudPresenceHistoricalSelection(
            choice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
            selectedStartAt = 20 * MINUTE,
            effectiveStartAt = 20 * MINUTE,
            throughAt = 80 * MINUTE,
            zoneId = "UTC",
        )

        val refiles = cloudPresenceSessionRefiles(
            sessions = listOf(original),
            ownedAppIds = setOf(GAME),
            intervals = listOf(
                interval(startAt = 0L, endAt = 50 * MINUTE),
                interval(startAt = 50 * MINUTE, endAt = 100 * MINUTE),
            ),
            range = selection,
        )

        assertEquals(1, refiles.size)
        val replacement = refiles.single().replacement
        assertEquals(listOf(5, 5), replacement.map { it.minutes })
        assertEquals(original.minutes, replacement.sumOf { it.minutes })
        assertEquals(listOf(20 * MINUTE, 50 * MINUTE), replacement.map { it.startAt })
        assertEquals(listOf(50 * MINUTE, 80 * MINUTE), replacement.map { it.endAt })
        assertTrue(replacement.all { it.startAt >= selection.effectiveStartAt && it.endAt!! <= selection.throughAt })
        assertTrue(replacement.zipWithNext().all { (first, next) -> first.endAt!! <= next.startAt })
    }

    @Test
    fun sessionWithNoTimeInsideSelectedRangeIsNotChanged() {
        val outside = session(appId = GAME, startAt = 0L, endAt = 10L, minutes = 10)
        val selection = CloudPresenceHistoricalSelection(
            choice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
            selectedStartAt = 20L,
            effectiveStartAt = 20L,
            throughAt = 80L,
            zoneId = "UTC",
        )

        val refiles = cloudPresenceSessionRefiles(
            sessions = listOf(outside),
            ownedAppIds = setOf(GAME),
            intervals = listOf(interval(startAt = 0L, endAt = 100L)),
            range = selection,
        )

        assertTrue(refiles.isEmpty())
    }

    @Test
    fun splitPlacementKeepsEachGameTotalExact() {
        val original = session(appId = GAME, startAt = 0L, endAt = 20 * MINUTE, minutes = 10).copy(
            recoveredSharedPlay = RecoveredSharedPlayState.PARTIAL,
            timingInformedSteamPlay = TimingInformedSteamPlayState.UNKNOWN,
        )

        val refiles = cloudPresenceSessionRefiles(
            sessions = listOf(original),
            ownedAppIds = setOf(GAME),
            intervals = listOf(
                interval(startAt = 0L, endAt = 8 * MINUTE),
                interval(startAt = 12 * MINUTE, endAt = 20 * MINUTE),
            ),
        )

        assertEquals(1, refiles.size)
        assertEquals(10, refiles.single().replacement.sumOf { it.minutes })
        assertEquals(listOf(5, 5), refiles.single().replacement.map { it.minutes })
        assertTrue(refiles.single().replacement.all {
            it.recoveredSharedPlay == RecoveredSharedPlayState.PARTIAL &&
                it.timingInformedSteamPlay == TimingInformedSteamPlayState.FULL
        })
    }

    @Test
    fun duplicateOverlapAndAnInteriorGapCannotJustifyRefilingUnsupportedMinutes() {
        val original = session(GAME, 0L, 40 * MINUTE, 35)
        val partiallyCovered = interval(
            startAt = 0L, endAt = 38 * MINUTE,
            coverageLapseFrom = 10 * MINUTE, coverageLapseRecoveredAt = 14 * MINUTE,
        )
        val evidence = listOf(partiallyCovered, partiallyCovered, interval(16 * MINUTE, 20 * MINUTE))
        assertTrue(cloudPresenceSessionRefiles(listOf(original), setOf(GAME), evidence).isEmpty())

        val replacement = cloudPresenceSessionRefiles(
            listOf(original.copy(minutes = 34)), setOf(GAME), evidence,
        ).single().replacement
        assertEquals(34, replacement.sumOf { it.minutes })
        assertTrue(replacement.all { it.minutes <= (it.endAt!! - it.startAt) / MINUTE })
        assertTrue(replacement.none { it.startAt < 14 * MINUTE && it.endAt!! > 10 * MINUTE })
        assertTrue(replacement.zipWithNext().all { (before, after) -> before.endAt!! <= after.startAt })
    }

    @Test
    fun outsideRejectedAndFamilySharedRowsAreUntouched() {
        val outside = session(appId = GAME, startAt = 0L, endAt = 100L, minutes = 10)
        val rejected = session(appId = GAME, startAt = 200L, endAt = 300L, minutes = 10)
        val shared = session(appId = SHARED, startAt = 0L, endAt = 100L, minutes = 10)

        val refiles = cloudPresenceSessionRefiles(
            sessions = listOf(outside, rejected, shared),
            ownedAppIds = setOf(GAME),
            intervals = listOf(
                interval(startAt = 500L, endAt = 600L),
                interval(
                    startAt = 200L,
                    endAt = 300L,
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = 300L,
                    coverageLapseFrom = 210_000L,
                    coverageLapseRecoveredAt = 910_000L,
                ),
            ),
        )

        assertTrue(refiles.isEmpty())
    }

    private fun session(appId: Long, startAt: Long, endAt: Long, minutes: Int) = Session(
        id = appId,
        appId = appId,
        startAt = startAt,
        endAt = endAt,
        minutes = minutes,
        open = false,
    )

    private fun interval(
        startAt: Long,
        endAt: Long,
        coverage: CloudCoverageState = CloudCoverageState.CONTINUOUS,
        observedUntil: Long? = null,
        coverageLapseFrom: Long? = null,
        coverageLapseRecoveredAt: Long? = null,
    ) = CloudPresenceInterval(
        appId = GAME,
        gameName = "Portal",
        startAt = startAt,
        endAt = endAt,
        ongoing = false,
        coverage = coverage,
        observedUntil = observedUntil,
        coverageLapseFrom = coverageLapseFrom,
        coverageLapseRecoveredAt = coverageLapseRecoveredAt,
        mayHaveStartedBefore = false,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val GAME = 440L
        const val SHARED = 441L
    }
}
