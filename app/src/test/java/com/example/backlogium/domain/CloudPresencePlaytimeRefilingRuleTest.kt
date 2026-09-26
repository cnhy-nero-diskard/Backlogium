package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresencePlaytimeRefilingRuleTest {
    @Test
    fun splitPlacementKeepsEachGameTotalExact() {
        val original = session(appId = GAME, startAt = 0L, endAt = 1_000L, minutes = 10).copy(
            recoveredSharedPlay = RecoveredSharedPlayState.PARTIAL,
            timingInformedSteamPlay = TimingInformedSteamPlayState.UNKNOWN,
        )

        val refiles = cloudPresenceSessionRefiles(
            sessions = listOf(original),
            ownedAppIds = setOf(GAME),
            intervals = listOf(
                interval(startAt = 0L, endAt = 400L),
                interval(startAt = 600L, endAt = 1_000L),
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
        const val GAME = 440L
        const val SHARED = 441L
    }
}
