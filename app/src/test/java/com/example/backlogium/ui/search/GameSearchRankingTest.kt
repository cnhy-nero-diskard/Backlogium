package com.example.backlogium.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSearchRankingTest {

    @Test
    fun resolvesEachTierFromStrongestToWeakest() {
        assertEquals(
            GameSearchMatchTier.EXACT_NAME,
            gameSearchMatchTier("red", "Red", emptyList()),
        )
        assertEquals(
            GameSearchMatchTier.NAME_PREFIX,
            gameSearchMatchTier("red", "Red Dead Redemption", emptyList()),
        )
        assertEquals(
            GameSearchMatchTier.WORD_PREFIX,
            gameSearchMatchTier("red", "The Red Door", emptyList()),
        )
        assertEquals(
            GameSearchMatchTier.NAME_SUBSTRING,
            gameSearchMatchTier("red", "Hundred Days", emptyList()),
        )
        assertEquals(
            GameSearchMatchTier.GENRE_LABEL,
            gameSearchMatchTier("red", "Portal", listOf("Action", "Redemption")),
        )
    }

    @Test
    fun recognizesCaseTransitionAsAWordBoundary() {
        assertEquals(
            GameSearchMatchTier.WORD_PREFIX,
            gameSearchMatchTier("dead", "RedDead", emptyList()),
        )
    }

    @Test
    fun nameMatchWinsWhenGenreAlsoMatches() {
        assertEquals(
            GameSearchMatchTier.NAME_PREFIX,
            gameSearchMatchTier("action", "Action RPG", listOf("Action")),
        )
    }

    @Test
    fun blankQueryHasNoRanking() {
        assertNull(gameSearchMatchTier("  ", "Portal", listOf("Action")))
    }
}
