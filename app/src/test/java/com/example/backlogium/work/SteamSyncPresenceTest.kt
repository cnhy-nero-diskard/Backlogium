package com.example.backlogium.work

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamSyncPresenceTest {

    @Test
    fun backgroundPresenceDecision_doesNotPreventOwnedGamesFetchOrStartAService() = runTest {
        var fetches = 0
        var notAttempted = 0

        val result = fetchOwnedGamesAfterPresenceDecision(
            gameDetected = true,
            recordPresenceNotAttempted = { notAttempted++ },
            fetchOwnedGames = {
                fetches++
                "owned-games"
            },
        )

        assertTrue(result.isSuccess)
        assertEquals("owned-games", result.getOrNull())
        assertEquals(1, fetches)
        assertEquals(1, notAttempted)
    }
}
