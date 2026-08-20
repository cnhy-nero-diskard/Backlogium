package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameListDensityTest {

    @Test
    fun ladder_exposesExpectedFields() {
        assertEquals(
            setOf(
                GameListField.IDENTITY,
                GameListField.PLAYTIME,
                GameListField.COMPLETION_PROGRESS,
                GameListField.ACHIEVEMENT_COUNT,
                GameListField.XP_CONTRIBUTION,
                GameListField.CURRENTLY_PLAYING,
            ),
            GameListDensity.LIST.visibleFields,
        )
        assertEquals(
            setOf(
                GameListField.IDENTITY,
                GameListField.PLAYTIME,
                GameListField.COMPLETION_PROGRESS,
                GameListField.ACHIEVEMENT_COUNT,
                GameListField.CURRENTLY_PLAYING,
            ),
            GameListDensity.GRID.visibleFields,
        )
        assertEquals(
            setOf(GameListField.IDENTITY, GameListField.CURRENTLY_PLAYING),
            GameListDensity.COMPACT_GRID.visibleFields,
        )
    }

    @Test
    fun ladder_isStrictlyMonotonic() {
        assertTrue(GameListDensity.GRID.isStrictSubsetOf(GameListDensity.LIST))
        assertTrue(GameListDensity.COMPACT_GRID.isStrictSubsetOf(GameListDensity.GRID))
    }

    /**
     * The split of the old `BADGES` rung is the whole reason the achievement count can reach the
     * grid: the count drops one rung later than the XP badge, and the chain still holds. Asserted
     * separately from the field sets above so a future reshuffle that keeps the ladder monotonic
     * but moves XP into the grid fails here rather than silently changing what a grid cell shows.
     */
    @Test
    fun achievementCountDropsOneRungLaterThanXp() {
        assertTrue(GameListDensity.LIST.showsAchievementCount)
        assertTrue(GameListDensity.LIST.showsXpContribution)

        assertTrue(GameListDensity.GRID.showsAchievementCount)
        assertFalse(GameListDensity.GRID.showsXpContribution)

        assertFalse(GameListDensity.COMPACT_GRID.showsAchievementCount)
        assertFalse(GameListDensity.COMPACT_GRID.showsXpContribution)

        assertTrue(GameListDensity.GRID.isStrictSubsetOf(GameListDensity.LIST))
        assertTrue(GameListDensity.COMPACT_GRID.isStrictSubsetOf(GameListDensity.GRID))
    }

    @Test
    fun unknownStoredValue_fallsBackToList() {
        assertEquals(GameListDensity.LIST, GameListDensity.fromStored("from-a-future-build"))
        assertEquals(GameListDensity.LIST, GameListDensity.fromStored(null))
    }
}
