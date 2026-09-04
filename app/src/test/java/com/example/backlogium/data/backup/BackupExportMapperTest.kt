package com.example.backlogium.data.backup

import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.domain.GameSource
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
