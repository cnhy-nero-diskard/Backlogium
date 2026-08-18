package com.example.backlogium.work

import com.example.backlogium.domain.SessionDiffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamSyncAccountBoundaryTest {

    @Test
    fun storedBaselineCannotBeDiffedAgainstAnotherAccount() {
        assertFalse(canDiffAgainstAccount("76561198000000000", "76561198000000001"))
    }

    @Test
    fun sameAccountAndFirstSyncAreAllowed() {
        assertTrue(canDiffAgainstAccount("76561198000000000", "76561198000000000"))
        assertTrue(canDiffAgainstAccount(null, "76561198000000001"))
    }

    @Test
    fun accountBLowerTotalsAreBaselinedWithoutSessionOrSuppression() {
        val result = SessionDiffer().baseline(
            listOf(SessionDiffer.PollGame(appId = 440L, playtimeForever = 15)),
        )

        assertTrue(result.actions.isEmpty())
        assertTrue(result.playedDeltaByAppId.isEmpty())
        assertEquals(15, result.newLastPlaytime[440L])
    }

    @Test
    fun accountBHigherTotalsAreBaselinedWithoutFabricatedSession() {
        val result = SessionDiffer().baseline(
            listOf(SessionDiffer.PollGame(appId = 440L, playtimeForever = 500)),
        )

        assertTrue(result.actions.isEmpty())
        assertTrue(result.playedDeltaByAppId.isEmpty())
    }
}
