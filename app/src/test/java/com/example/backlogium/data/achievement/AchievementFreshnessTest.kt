package com.example.backlogium.data.achievement

import com.example.backlogium.domain.GameSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementFreshnessTest {

    @Test
    fun `hot games are selected and warm cold never are classified`() {
        val games = listOf(
            ownedGame(1, forever = 100, weeks = 10),
            ownedGame(2, forever = 200, weeks = 0),
            ownedGame(3, forever = 0, weeks = 0),
        )
        val deltas = mapOf(1L to 5)
        val metadata = emptyMap<Long, AchievementFreshness.SyncMetadata>()

        val result = AchievementFreshness.selectByTier(0L, games, deltas, metadata)

        assertEquals(listOf(1L), result.hot)
        assertEquals(emptyList<Long>(), result.warm)
        assertEquals(listOf(2L), result.cold)
        assertEquals(listOf(3L), result.never)
        // Game 2 (cold, no metadata) is missing-data eligible; game 3 (never) is not.
        assertEquals(listOf(1L, 2L), result.inlineSelected)
    }

    @Test
    fun `warm games are selected when there is no delta`() {
        val games = listOf(
            ownedGame(1, forever = 100, weeks = 10),
        )
        val result = AchievementFreshness.selectByTier(0L, games, emptyMap(), emptyMap())

        assertEquals(emptyList<Long>(), result.hot)
        assertEquals(listOf(1L), result.warm)
        assertEquals(listOf(1L), result.inlineSelected)
    }

    @Test
    fun `never-played games are excluded even when data is missing`() {
        val games = listOf(
            ownedGame(1, forever = 0, weeks = 0),
        )
        val result = AchievementFreshness.selectByTier(0L, games, emptyMap(), emptyMap())

        assertEquals(emptyList<Long>(), result.hot)
        assertEquals(emptyList<Long>(), result.warm)
        assertEquals(emptyList<Long>(), result.cold)
        assertEquals(listOf(1L), result.never)
        assertEquals(emptyList<Long>(), result.inlineSelected)
    }

    @Test
    fun `missing-data override is capped and oldest-first`() {
        val games = (1L..30L).map {
            ownedGame(it, forever = it * 10, weeks = 0)
        }
        val metadata = mapOf(
            1L to metadata(1, playerStateFetchedAt = 1000),
            2L to metadata(2, playerStateFetchedAt = 500),
        )
        val result = AchievementFreshness.selectByTier(
            now = 0L,
            ownedGames = games,
            playtimeDeltaByAppId = emptyMap(),
            metadataByAppId = metadata,
        )

        // All 30 games are cold; 1 and 2 have metadata, so 28 are missing-data eligible.
        assertEquals(30, result.cold.size)
        // Override is capped at 25.
        assertEquals(25, result.missingDataOverride.size)
        // Absent metadata comes before present; among present, older fetchedAt first.
        // So 3-27 (absent) should be selected before 2 (fetchedAt=500) and 1 (fetchedAt=1000).
        assertEquals(
            "expected 3..27 but was ${result.missingDataOverride}",
            (3L..27L).toList(),
            result.missingDataOverride,
        )
    }

    @Test
    fun `hot games are not duplicated in missing-data override`() {
        val games = listOf(
            ownedGame(1, forever = 100, weeks = 0),
        )
        val result = AchievementFreshness.selectByTier(
            0L,
            games,
            mapOf(1L to 5),
            emptyMap(),
        )

        assertEquals(listOf(1L), result.hot)
        assertEquals(emptyList<Long>(), result.missingDataOverride)
        assertEquals(listOf(1L), result.inlineSelected)
    }

    @Test
    fun `zero delta does not make a game hot`() {
        val games = listOf(
            ownedGame(1, forever = 100, weeks = 0),
        )
        val result = AchievementFreshness.selectByTier(
            0L,
            games,
            mapOf(1L to 0),
            emptyMap(),
        )

        assertEquals(emptyList<Long>(), result.hot)
        assertEquals(listOf(1L), result.cold)
    }

    /**
     * fix-shared-game-achievement-visibility: an owned game with zero playtime is genuinely
     * never-played and is excluded, but a family-shared game's zero *locally tracked* playtime
     * does not mean the same thing — Backlogium may simply never have observed a session for a
     * game completed before it was admitted. Such a game must never land in NEVER.
     */
    @Test
    fun `a family-shared game with zero tracked playtime is cold, not never`() {
        val games = listOf(
            sharedGame(1, forever = 0),
            ownedGame(2, forever = 0, weeks = 0),
        )
        val result = AchievementFreshness.selectByTier(0L, games, emptyMap(), emptyMap())

        assertEquals("the shared game is cold, and missing-data eligible", listOf(1L), result.cold)
        assertEquals("the owned game is still excluded", listOf(2L), result.never)
        assertEquals(listOf(1L), result.inlineSelected)
    }

    /**
     * fix-shared-game-achievement-visibility, task 3.3: an already-admitted family-shared game
     * with no stored achievement data — including one that would previously have been classified
     * NEVER on zero tracked playtime alone — is naturally swept up by the existing bounded,
     * oldest-first missing-data selection on its library's next sync. No separate backfill
     * mechanism is needed once tiering stops excluding it.
     */
    @Test
    fun `several already-admitted shared games with no stored data are missing-data eligible up to the cap`() {
        val sharedGames = (1L..30L).map { sharedGame(it, forever = 0) }

        val result = AchievementFreshness.selectByTier(0L, sharedGames, emptyMap(), emptyMap())

        assertEquals("every shared game is cold, none are excluded as never-played", 30, result.cold.size)
        assertEquals(emptyList<Long>(), result.never)
        assertEquals(
            "the missing-data override is still bounded, oldest-first, same as for owned games",
            25,
            result.missingDataOverride.size,
        )
    }

    @Test
    fun `a family-shared game with stored data and zero tracked playtime stays cold, not missing-data eligible`() {
        val games = listOf(sharedGame(1, forever = 0))
        val metadata = mapOf(1L to metadata(1, playerStateFetchedAt = 500))

        val result = AchievementFreshness.selectByTier(0L, games, emptyMap(), metadata)

        assertEquals(listOf(1L), result.cold)
        assertEquals(emptyList<Long>(), result.missingDataOverride)
    }

    private fun ownedGame(
        appId: Long,
        forever: Long,
        weeks: Long,
    ) = AchievementFreshness.OwnedGame(
        appId = appId,
        playtimeForever = forever,
        playtime2Weeks = weeks,
        source = GameSource.STEAM_OWNED,
    )

    private fun sharedGame(
        appId: Long,
        forever: Long,
    ) = AchievementFreshness.OwnedGame(
        appId = appId,
        playtimeForever = forever,
        playtime2Weeks = 0L,
        source = GameSource.FAMILY_SHARED,
    )

    private fun metadata(
        appId: Long,
        playerStateFetchedAt: Long,
    ) = AchievementFreshness.SyncMetadata(
        appId = appId,
        playerStateFetchedAt = playerStateFetchedAt,
    )
}
