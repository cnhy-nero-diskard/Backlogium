package com.example.backlogium.data.achievement

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

    private fun ownedGame(
        appId: Long,
        forever: Long,
        weeks: Long,
    ) = AchievementFreshness.OwnedGame(
        appId = appId,
        playtimeForever = forever,
        playtime2Weeks = weeks,
    )

    private fun metadata(
        appId: Long,
        playerStateFetchedAt: Long,
    ) = AchievementFreshness.SyncMetadata(
        appId = appId,
        playerStateFetchedAt = playerStateFetchedAt,
    )
}
