package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `displayedPlaytimeMinutes` had no dedicated test before add-shared-game-playtime-and-filter —
 * exercised only indirectly through the ViewModels that call it. Covers both branches plus the
 * new manual-minutes term this change adds.
 */
class GameSourceTest {

    @Test
    fun ownedGameShowsSteamsLifetimeTotal() {
        assertEquals(
            500,
            GameSource.STEAM_OWNED.displayedPlaytimeMinutes(
                steamPlaytimeMinutes = 500,
                trackedMinutes = 30,
            ),
        )
    }

    @Test
    fun ownedGameIgnoresManualMinutesEvenIfSomehowNonzero() {
        assertEquals(
            500,
            GameSource.STEAM_OWNED.displayedPlaytimeMinutes(
                steamPlaytimeMinutes = 500,
                trackedMinutes = 30,
                manualSharedMinutes = 999,
            ),
        )
    }

    @Test
    fun sharedGameShowsTrackedMinutesWhenNoManualEstimate() {
        assertEquals(
            30,
            GameSource.FAMILY_SHARED.displayedPlaytimeMinutes(
                steamPlaytimeMinutes = 0,
                trackedMinutes = 30,
            ),
        )
    }

    @Test
    fun sharedGamesManualEstimateIsAdditiveWithTrackedMinutes() {
        assertEquals(
            90,
            GameSource.FAMILY_SHARED.displayedPlaytimeMinutes(
                steamPlaytimeMinutes = 0,
                trackedMinutes = 30,
                manualSharedMinutes = 60,
            ),
        )
    }

    @Test
    fun defaultManualMinutesParameterIsZero() {
        assertEquals(
            GameSource.FAMILY_SHARED.displayedPlaytimeMinutes(0, 30),
            GameSource.FAMILY_SHARED.displayedPlaytimeMinutes(0, 30, manualSharedMinutes = 0),
        )
    }
}
