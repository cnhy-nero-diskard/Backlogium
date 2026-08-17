package com.example.backlogium.work

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamSyncPresenceTest {

    @Test
    fun throwingPresenceStarter_doesNotPreventOwnedGamesFetch() = runTest {
        var fetches = 0

        val result = fetchOwnedGamesAfterPresenceStart(
            gameDetected = true,
            startPresence = { error("background foreground-service start refused") },
            fetchOwnedGames = {
                fetches++
                "owned-games"
            },
        )

        assertTrue(result.isSuccess)
        assertEquals("owned-games", result.getOrNull())
        assertEquals(1, fetches)
    }
}
