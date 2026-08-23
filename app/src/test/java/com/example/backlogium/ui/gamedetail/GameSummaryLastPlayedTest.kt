package com.example.backlogium.ui.gamedetail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three answers the summary's last-played row can give.
 *
 * Worth its own test because two of them are easy to conflate and the conflation is a false
 * statement about the player's own history: Steam omits `rtime_last_played` for some games it has
 * hours for, so "no date" must not read as "never played".
 */
class GameSummaryLastPlayedTest {

    @Test
    fun `a known date reads as a date`() {
        assertEquals(
            LastPlayed.At(1_700_000_000_000L),
            GameSummaryUi(playtimeMinutes = 2_400, lastPlayedAt = 1_700_000_000_000L).lastPlayed,
        )
    }

    @Test
    fun `no recorded playtime reads as never played`() {
        assertEquals(
            LastPlayed.Never,
            GameSummaryUi(playtimeMinutes = 0, lastPlayedAt = null).lastPlayed,
        )
    }

    @Test
    fun `playtime with no date reads as unknown rather than never`() {
        assertEquals(
            LastPlayed.Unknown,
            GameSummaryUi(playtimeMinutes = 2_400, lastPlayedAt = null).lastPlayed,
        )
    }

    @Test
    fun `never played wins even when a date somehow exists`() {
        // Playtime is the authority on "has this been played at all". A date on a zero-playtime
        // game is Steam contradicting itself, and the honest reading is the playtime.
        assertEquals(
            LastPlayed.Never,
            GameSummaryUi(playtimeMinutes = 0, lastPlayedAt = 1_700_000_000_000L).lastPlayed,
        )
    }
}
