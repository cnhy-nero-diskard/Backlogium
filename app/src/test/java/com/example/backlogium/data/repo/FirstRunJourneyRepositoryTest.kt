package com.example.backlogium.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.backlogium.domain.FirstRunPhase
import com.example.backlogium.work.setup.FakeSetupStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FirstRunJourneyRepositoryTest {
    private fun withRepository(block: suspend (FirstRunJourneyRepository) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("first-run-journey").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { root.resolve("journey.preferences_pb") }
            block(FirstRunJourneyRepository(data))
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun credentials(steamId: String?) = object : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured? =
            steamId?.let { CredentialsState.Configured(apiKey = "key", steamId = it) }
    }

    private suspend fun setupStore(claim: Boolean) = FakeSetupStateStore().apply {
        setFirstRunSetupActive(claim)
    }

    @Test fun restoreLegacyMapsAnOldUnfinishedClaimToSetupExactlyOnce() = withRepository { repo ->
        val store = setupStore(claim = true)
        repo.restoreLegacy(credentials("account-a"), store)
        assertEquals(FirstRunPhase.SETUP, repo.current()?.phase)
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.HISTORY_CHOICE)

        // The MIGRATED guard means a second restore (e.g. Home startup + onboarding) cannot
        // overwrite the history phase back to SETUP.
        store.setFirstRunSetupActive(true)
        repo.restoreLegacy(credentials("account-a"), store)
        assertEquals(FirstRunPhase.HISTORY_CHOICE, repo.current()?.phase)
    }

    @Test fun restoreLegacyLeavesAConfiguredInstallWithoutAClaimOutOfOnboarding() = withRepository { repo ->
        repo.restoreLegacy(credentials("account-a"), setupStore(claim = false))
        assertNull(repo.current())

        // A claim without credentials (e.g. state left mid-migration) never fabricates a journey.
        repo.restoreLegacy(credentials(null), setupStore(claim = true))
        assertNull(repo.current())
    }

    @Test fun restoreLegacyPreservesAnExistingExplicitJourney() = withRepository { repo ->
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.HISTORY_CHOICE)

        repo.restoreLegacy(credentials("account-a"), setupStore(claim = false))
        assertEquals(FirstRunPhase.HISTORY_CHOICE, repo.current()?.phase)
    }

    @Test fun restoreLegacyPreservesTerminalHistoricalResults() = withRepository { repo ->
        // A completed journey stays complete; a stale/absent legacy claim cannot reopen it.
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.HISTORY_CHOICE)
        repo.transition("account-a", setOf(FirstRunPhase.HISTORY_CHOICE), FirstRunPhase.COMPLETE)

        repo.restoreLegacy(credentials("account-a"), setupStore(claim = true))
        assertFalse(repo.current()!!.owed)
        assertEquals(FirstRunPhase.COMPLETE, repo.current()?.phase)
    }

    @Test fun oldUnfinishedClaimMigratesToSetupOnlyOnce() = withRepository { repo ->
        repo.migrateLegacy("account-a", true)
        assertEquals(FirstRunPhase.SETUP, repo.current()?.phase)
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.HISTORY_CHOICE)
        repo.migrateLegacy("account-a", true)
        assertEquals(FirstRunPhase.HISTORY_CHOICE, repo.current()?.phase)
    }

    @Test fun configuredInstallWithoutClaimDoesNotOweOnboarding() = withRepository { repo ->
        repo.migrateLegacy("account-a", false)
        assertNull(repo.current())
        repo.migrateLegacy("account-b", true)
        assertNull(repo.current())
    }

    @Test fun explicitExitSurvivesLateSuccessfulImport() = withRepository { repo ->
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.HISTORY_CHOICE)
        repo.transition("account-a", setOf(FirstRunPhase.HISTORY_CHOICE), FirstRunPhase.IMPORT_REQUESTED, "request-a")
        assertTrue(repo.current()!!.owed)
        repo.transition("account-a", setOf(FirstRunPhase.IMPORT_REQUESTED), FirstRunPhase.DEFERRED)
        assertFalse(repo.completeImport("account-a", "request-a"))
        assertFalse(repo.current()!!.owed)
        assertEquals("request-a", repo.current()?.importRequestId)
    }

    @Test fun completionRequiresExactRequestAndAccount() = withRepository { repo ->
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.IMPORT_REQUESTED, "request-a")
        assertFalse(repo.completeImport("account-a", "older-request"))
        assertFalse(repo.completeImport("account-b", "request-a"))
        assertTrue(repo.completeImport("account-a", "request-a"))
        assertEquals(FirstRunPhase.COMPLETE, repo.current()?.phase)
        repo.migrateLegacy("account-a", true)
        assertEquals(FirstRunPhase.COMPLETE, repo.current()?.phase)
    }

    @Test fun replacementAccountRejectsOldPhaseAndRequestCallbacks() = withRepository { repo ->
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.IMPORT_REQUESTED, "request-a")
        repo.claim("account-b")
        assertFalse(repo.completeImport("account-a", "request-a"))
        assertFalse(repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.DEFERRED))
        assertEquals("account-b", repo.current()?.accountSteamId)
        assertEquals(FirstRunPhase.SETUP, repo.current()?.phase)
    }

    // --------------------------------------------------------------- settleDeferredRequest

    @Test fun settleDeferredRequestClearsOnlyAnExactDeferredRequest() = withRepository { repo ->
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.IMPORT_REQUESTED, "request-a")
        repo.transition("account-a", setOf(FirstRunPhase.IMPORT_REQUESTED), FirstRunPhase.DEFERRED)

        // Wrong account and wrong request id are untouched.
        assertFalse(repo.settleDeferredRequest("account-b", "request-a"))
        assertFalse(repo.settleDeferredRequest("account-a", "other-request"))
        assertEquals("request-a", repo.current()?.importRequestId)
        assertFalse(repo.current()!!.owed)

        // The exact fully-successful settlement clears only the pointer and keeps DEFERRED.
        assertTrue(repo.settleDeferredRequest("account-a", "request-a"))
        assertEquals(FirstRunPhase.DEFERRED, repo.current()?.phase)
        assertNull(repo.current()?.importRequestId)
        assertFalse(repo.current()!!.owed)
    }

    @Test fun settleDeferredRequestLeavesNonDeferredPhasesUntouched() = withRepository { repo ->
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.IMPORT_REQUESTED, "request-a")

        // Still IMPORT_REQUESTED (unanswered/owed): the exact-request settle must not clear it.
        assertFalse(repo.settleDeferredRequest("account-a", "request-a"))
        assertEquals("request-a", repo.current()?.importRequestId)
        assertTrue(repo.current()!!.owed)
    }

    @Test fun clearImportRequestClearsOnlyTheExactRequestForTheGivenPhase() = withRepository { repo ->
        repo.claim("account-a")
        repo.transition("account-a", setOf(FirstRunPhase.SETUP), FirstRunPhase.IMPORT_REQUESTED, "request-a")

        // Wrong phase set and wrong account leave the pointer untouched.
        assertFalse(repo.clearImportRequest("account-a", "request-a", setOf(FirstRunPhase.DEFERRED)))
        assertFalse(repo.clearImportRequest("account-b", "request-a", setOf(FirstRunPhase.IMPORT_REQUESTED)))
        assertEquals("request-a", repo.current()?.importRequestId)

        // Exact request + phase clears only the pointer; the phase is never moved by this method.
        assertTrue(repo.clearImportRequest("account-a", "request-a", setOf(FirstRunPhase.IMPORT_REQUESTED)))
        assertEquals(FirstRunPhase.IMPORT_REQUESTED, repo.current()?.phase)
        assertNull(repo.current()?.importRequestId)
        assertTrue(repo.current()!!.owed)
    }
}
