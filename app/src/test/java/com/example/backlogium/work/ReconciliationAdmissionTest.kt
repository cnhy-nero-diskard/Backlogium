package com.example.backlogium.work

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReconciliationAdmissionTest {
    private val marker = AccountChangeMarkerStore(RuntimeEnvironment.getApplication())
    private val coordinator = SteamSyncCoordinator()

    @Test fun `a forced request admitted for A cannot capture B after an account switch`() = runTest {
        marker.clear()
        val generationA = marker.generation()
        val credentials = mutableCredentials(ACCOUNT_A)

        // The queued A work is delayed until the durable B reset has completed.
        marker.markPending(ACCOUNT_B)
        credentials.current = ACCOUNT_B
        marker.clear()

        val admission = captureReconciliationAdmission(
            coordinator, marker, credentials, inputGeneration = generationA, tagGeneration = generationA,
        )

        assertNull(admission)
        assertEquals(ACCOUNT_B, credentials.current)
    }

    @Test fun `same-account reset still fences an older generation`() = runTest {
        marker.clear()
        val oldGeneration = marker.generation()
        val credentials = mutableCredentials(ACCOUNT_A)
        marker.markPending(ACCOUNT_A)
        marker.clear()

        assertNull(captureReconciliationAdmission(
            coordinator, marker, credentials, inputGeneration = oldGeneration, tagGeneration = oldGeneration,
        ))
        assertTrue(marker.generation() > oldGeneration)
    }

    @Test fun `input and account tag disagreement is denied before credentials are read`() = runTest {
        marker.clear()
        val credentials = mutableCredentials(ACCOUNT_A)

        assertNull(captureReconciliationAdmission(
            coordinator, marker, credentials,
            inputGeneration = marker.generation(), tagGeneration = marker.generation() + 1,
        ))
        assertEquals(0, credentials.reads)
    }

    private fun mutableCredentials(account: String) = MutableCredentials(account)

    private class MutableCredentials(var current: String) : CredentialsProvider {
        var reads = 0
        override suspend fun currentCredentials(): CredentialsState.Configured {
            reads++
            return CredentialsState.Configured("fixture-key", current)
        }
    }

    private companion object {
        const val ACCOUNT_A = "76561198000000000"
        const val ACCOUNT_B = "76561198000000001"
    }
}
