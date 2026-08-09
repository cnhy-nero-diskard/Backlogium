package com.example.backlogium.domain

import org.junit.Assert.assertEquals
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
                GameListField.BADGES,
                GameListField.CURRENTLY_PLAYING,
            ),
            GameListDensity.LIST.visibleFields,
        )
        assertEquals(
            setOf(
                GameListField.IDENTITY,
                GameListField.PLAYTIME,
                GameListField.COMPLETION_PROGRESS,
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

    @Test
    fun unknownStoredValue_fallsBackToList() {
        assertEquals(GameListDensity.LIST, GameListDensity.fromStored("from-a-future-build"))
        assertEquals(GameListDensity.LIST, GameListDensity.fromStored(null))
    }
}
