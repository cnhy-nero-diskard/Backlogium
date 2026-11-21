package com.example.backlogium.data.backup

import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.GameSource
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Game.backupXpMinutes` — extracted from `BackupExportMapper.buildComputed`'s two duplicate sums
 * (add-shared-game-playtime-and-filter) — is what keeps a restored backup's snapshotted XP
 * consistent with what `GamificationUpdater.compute()` would produce live.
 */
class BackupExportMapperTest {

    @Test
    fun ownedGamesBackfillIsIncluded() {
        val game = game(source = GameSource.STEAM_OWNED, backfillMinutes = 5_000)

        assertEquals(5_100, game.backupXpMinutes(trackedMinutes = 100))
    }

    @Test
    fun sharedGamesManualEstimateIsIncluded() {
        val game = game(source = GameSource.FAMILY_SHARED, manualSharedMinutes = 60)

        assertEquals(90, game.backupXpMinutes(trackedMinutes = 30))
    }

    @Test
    fun neitherOffsetSetIsJustTrackedMinutes() {
        val game = game(source = GameSource.STEAM_OWNED)

        assertEquals(30, game.backupXpMinutes(trackedMinutes = 30))
    }

    @Test
    fun datedTimelineIndexesSessionSharesAllocationsAndRecipeMinutes() {
        val appId = 1L
        val wedSessionStart = LocalDate.parse("2026-10-21")
            .atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val timeline = CanonicalDatedXpTimeline(
            sessions = listOf(
                Session(
                    appId = appId,
                    startAt = LocalDate.parse("2026-10-20").atStartOfDay(ZoneOffset.UTC)
                        .toInstant().toEpochMilli(),
                    minutes = 30,
                    open = false,
                ),
                Session(appId = appId, startAt = wedSessionStart, minutes = 100, open = false),
            ),
            recovery = BackupRecovery(
                timedPlacements = listOf(
                    BackupTimedPlacement(
                        placementId = "timed-share",
                        contributionId = "timed-contribution",
                        account = "test-account",
                        appId = appId,
                        revision = 1,
                        minutes = 20,
                        creditedDate = "2026-10-22",
                        zone = "UTC",
                        sessionStartAt = java.time.Instant.ofEpochMilli(wedSessionStart).toString(),
                    ),
                ),
                unknownAllocations = listOf(
                    BackupUnknownAllocation(
                        placementId = "standalone",
                        contributionId = "standalone-contribution",
                        account = "test-account",
                        appId = appId,
                        revision = 1,
                        date = "2026-10-23",
                        minutes = 15,
                    ),
                    BackupUnknownAllocation(
                        placementId = "recipe-materialized",
                        contributionId = "recipe-contribution",
                        account = "test-account",
                        appId = appId,
                        revision = 1,
                        date = "2026-10-20",
                        minutes = 3,
                    ),
                    BackupUnknownAllocation(
                        placementId = "pending",
                        contributionId = "pending-contribution",
                        account = "test-account",
                        appId = appId,
                        revision = 1,
                        minutes = 99,
                        pendingDate = true,
                    ),
                    BackupUnknownAllocation(
                        placementId = "superseded",
                        contributionId = "superseded-contribution",
                        account = "test-account",
                        appId = appId,
                        revision = 1,
                        date = "2026-10-23",
                        minutes = 100,
                        superseded = true,
                    ),
                ),
                recipes = listOf(
                    BackupAllocationRecipe(
                        contributionId = "recipe-contribution",
                        account = "test-account",
                        appId = appId,
                        revision = 1,
                        method = "MULTI_DAY_ESTIMATE",
                        quotient = 2,
                        remainderCount = 1,
                        dateStart = "2026-10-20",
                        dateEnd = "2026-10-23",
                        excludedDatesCsv = "2026-10-21",
                    ),
                ),
            ),
            zone = ZoneOffset.UTC,
        )

        assertEquals(33L, timeline.minutesBefore(appId, LocalDate.parse("2026-10-21")))
        assertEquals(113L, timeline.minutesBefore(appId, LocalDate.parse("2026-10-22")))
        assertEquals(135L, timeline.minutesBefore(appId, LocalDate.parse("2026-10-23")))
        assertEquals(152L, timeline.minutesBefore(appId, LocalDate.parse("2026-10-24")))
    }

    private fun game(
        source: GameSource,
        backfillMinutes: Int = 0,
        manualSharedMinutes: Int = 0,
    ) = Game(
        appId = 1L,
        name = "Game",
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
        source = source,
        backfillMinutes = backfillMinutes,
        manualSharedMinutes = manualSharedMinutes,
    )
}
