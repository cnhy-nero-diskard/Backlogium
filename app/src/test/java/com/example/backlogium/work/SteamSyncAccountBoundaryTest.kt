package com.example.backlogium.work

import com.example.backlogium.domain.SessionDiffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun syncWriteGuardRequiresClearMarkerAndMatchingCredentials() {
        assertTrue(isSyncForActiveAccount(null, OLD_ID, OLD_ID))
        assertTrue(isSyncForActiveAccount(null, null, null))
        assertFalse(isSyncForActiveAccount(NEW_ID, OLD_ID, OLD_ID))
        assertFalse(isSyncForActiveAccount(null, NEW_ID, OLD_ID))
        assertFalse(isSyncForActiveAccount(null, NEW_ID, null))
    }

    /**
     * An admitted old sync that fails after the account reset completes must leave the new
     * account untouched. The old run is paused before its failure writes ([recordError],
     * presence decision, diagnostics finish in `SteamSyncWorker`), the reset clears the
     * account-owned state under the coordinator barrier, then the old run resumes through the
     * same `withLock { if (isSyncForActiveAccount(...)) write }` guard the worker uses.
     */
    @Test
    fun admittedOldSyncFailureLeavesNoStateAfterAccountReset() = runTest {
        val coordinator = SteamSyncCoordinator()
        var markerSteamId: String? = null
        var currentSteamId: String? = OLD_ID
        var profileSteamId: String? = OLD_ID
        var profileError: String? = null
        val runs = mutableListOf<String>()
        val presence = mutableListOf<String>()

        val admitted = runAfterAccountChangeAdmission(
            coordinator = coordinator,
            accountChangePending = { markerSteamId != null },
            work = { true },
        )
        assertEquals(true, admitted)

        val pausedBeforeFailureWrite = CompletableDeferred<Unit>()
        val releaseFailureWrite = CompletableDeferred<Unit>()
        val oldSync = launch {
            pausedBeforeFailureWrite.complete(Unit)
            releaseFailureWrite.await()
            coordinator.withLock {
                if (isSyncForActiveAccount(markerSteamId, currentSteamId, OLD_ID)) {
                    profileError = "old error"
                    runs += "old run"
                    presence += "old presence"
                }
            }
        }
        pausedBeforeFailureWrite.await()

        // The reset the coordinator serializes: marker set while waiting, Room cleared under
        // the barrier, credentials promoted, marker cleared.
        markerSteamId = NEW_ID
        coordinator.withLock {
            profileSteamId = NEW_ID
            profileError = null
            runs.clear()
            presence.clear()
        }
        currentSteamId = NEW_ID
        markerSteamId = null

        releaseFailureWrite.complete(Unit)
        oldSync.join()

        assertEquals(NEW_ID, profileSteamId)
        assertNull(profileError)
        assertTrue(runs.isEmpty())
        assertTrue(presence.isEmpty())
    }

    @Test
    fun guardedWritesStillApplyWithoutAnAccountChange() = runTest {
        val coordinator = SteamSyncCoordinator()
        var profileError: String? = null
        var writes = 0

        coordinator.withLock {
            if (isSyncForActiveAccount(null, OLD_ID, OLD_ID)) {
                profileError = "sync failed"
                writes++
            }
        }

        assertEquals("sync failed", profileError)
        assertEquals(1, writes)
    }

    private companion object {
        const val OLD_ID = "76561198000000000"
        const val NEW_ID = "76561198000000001"
    }
}
