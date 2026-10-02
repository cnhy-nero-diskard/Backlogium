package com.example.backlogium.data.repo

import com.example.backlogium.domain.LibraryBaselineReadiness
import com.example.backlogium.domain.LibraryPollResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attributable library-poll outcomes and the account-scoped baseline readiness, as projected
 * by the pure boundary mappers in [LibraryPollRepository].
 *
 * The load-bearing cases are the rejections: a result or readiness manufactured from the wrong
 * work/account, from a generic `lastSyncAt`, or from a restored/legacy row would silently turn a
 * scheduler success into a committed library and an existing import UI into a false readiness,
 * which is exactly the defect set this milestone is hardening against.
 */
class LibraryPollRepositoryTest {

    private val repository = LibraryPollRepository()

    private val manualWork = "00000000-0000-0000-0000-000000000001"
    private val periodicWork = "00000000-0000-0000-0000-000000000002"
    private val accountA = "76561198000000000"
    private val accountB = "76561198000000001"
    private val confirmedAt = 1_700_000_000_000L
    private val lastSyncAt = 1_700_000_123_456L

    private fun request(work: String = manualWork, account: String = accountA) =
        LibraryPollRequest(workIdentity = work, accountSteamId = account)

    // ----------------------------------------------------------- result mapping

    @Test
    fun `a committed poll for the same work and account is committed`() {
        val result = repository.resultFor(
            request(),
            LibraryPollEvidence.Committed(workIdentity = manualWork, accountSteamId = accountA, gameCount = 137, lastSyncAt = lastSyncAt),
        )
        assertEquals(LibraryPollResult.Committed(137, lastSyncAt), result)
        assertFalse((result as LibraryPollResult.Committed).confirmedEmpty)
    }

    @Test
    fun `a confirmed empty library commits as a successful empty baseline`() {
        val result = repository.resultFor(
            request(),
            LibraryPollEvidence.Committed(workIdentity = manualWork, accountSteamId = accountA, gameCount = 0, lastSyncAt = lastSyncAt),
        )
        assertEquals(LibraryPollResult.Committed(0, lastSyncAt), result)
        assertTrue(result is LibraryPollResult.Committed)
        // The confirmed empty baseline is a success, not a privacy failure.
        assertTrue((result as LibraryPollResult.Committed).confirmedEmpty)
    }

    @Test
    fun `committed evidence from another work operation is unknown evidence`() {
        assertEquals(
            LibraryPollResult.Unknown,
            repository.resultFor(
                request(work = manualWork),
                LibraryPollEvidence.Committed(periodicWork, accountA, 137, lastSyncAt),
            ),
        )
    }

    @Test
    fun `committed evidence from another account is unknown evidence`() {
        assertEquals(
            LibraryPollResult.Unknown,
            repository.resultFor(
                request(account = accountA),
                LibraryPollEvidence.Committed(manualWork, accountB, 137, lastSyncAt),
            ),
        )
    }

    @Test
    fun `a worker exiting without credentials is not performed, not committed`() {
        val result = repository.resultFor(
            request(),
            LibraryPollEvidence.NotPerformed(
                manualWork,
                accountA,
                LibraryPollRefusal.MISSING_CREDENTIALS,
            ),
        )
        assertEquals(LibraryPollResult.NotPerformed.MissingCredentials, result)
        assertFalse(result is LibraryPollResult.Committed)
    }

    @Test
    fun `a poll refused at the account boundary is not performed, not committed`() {
        assertEquals(
            LibraryPollResult.NotPerformed.AccountAdmissionRefused,
            repository.resultFor(
                request(),
                LibraryPollEvidence.NotPerformed(
                    manualWork,
                    accountA,
                    LibraryPollRefusal.ACCOUNT_ADMISSION_REFUSED,
                ),
            ),
        )
    }

    @Test
    fun `an unconfirmed empty response is not performed and may indicate privacy`() {
        assertEquals(
            LibraryPollResult.NotPerformed.UnconfirmedEmpty,
            repository.resultFor(
                request(),
                LibraryPollEvidence.NotPerformed(
                    manualWork,
                    accountA,
                    LibraryPollRefusal.UNCONFIRMED_EMPTY,
                ),
            ),
        )
    }

    @Test
    fun `a failing poll is a recoverable failure`() {
        assertEquals(
            LibraryPollResult.RecoverableFailure("Network request timed out"),
            repository.resultFor(
                request(),
                LibraryPollEvidence.Failed(manualWork, accountA, "Network request timed out"),
            ),
        )
    }

    @Test
    fun `no evidence at all is unknown, never success`() {
        // A legacy finished job with no persisted domain outcome, or a pruned record: there is
        // nothing to classify, and nothing may be inferred from the absence.
        assertEquals(LibraryPollResult.Unknown, repository.resultFor(request(), evidence = null))
    }

    @Test
    fun `a retry scheduled operation without a terminal outcome is unknown, not a terminal failure`() {
        val result = repository.resultFor(request(), LibraryPollEvidence.Pending)
        assertEquals(LibraryPollResult.Unknown, result)
        // Retry scheduling must remain distinguishable from terminal failure.
        assertFalse(result is LibraryPollResult.RecoverableFailure)
    }

    @Test
    fun `not performed evidence from another account is unknown evidence`() {
        assertEquals(
            LibraryPollResult.Unknown,
            repository.resultFor(
                request(account = accountA),
                LibraryPollEvidence.NotPerformed(
                    manualWork,
                    accountB,
                    LibraryPollRefusal.MISSING_CREDENTIALS,
                ),
            ),
        )
    }

    @Test
    fun `failure evidence from another work operation is unknown evidence`() {
        assertEquals(
            LibraryPollResult.Unknown,
            repository.resultFor(
                request(work = periodicWork),
                LibraryPollEvidence.Failed(manualWork, accountA, "boom"),
            ),
        )
    }

    @Test
    fun `a blank work identity can never claim a committed result`() {
        assertEquals(
            LibraryPollResult.Unknown,
            repository.resultFor(
                request(work = "  "),
                LibraryPollEvidence.Committed(manualWork, accountA, 137, lastSyncAt),
            ),
        )
    }

    // ---------------------------------------------------------- readiness mapping

    @Test
    fun `a different manual request under the same unique name cannot inherit success`() {
        val replacementWork = "00000000-0000-0000-0000-000000000003"
        assertEquals(
            LibraryPollResult.Unknown,
            repository.resultFor(
                request(work = replacementWork),
                LibraryPollEvidence.Committed(manualWork, accountA, 137, lastSyncAt),
            ),
        )
    }

    @Test
    fun `a malformed committed count cannot claim success`() {
        assertEquals(
            LibraryPollResult.Unknown,
            repository.resultFor(
                request(),
                LibraryPollEvidence.Committed(manualWork, accountA, -1, lastSyncAt),
            ),
        )
    }

    @Test
    fun `a local same-account confirmation with no pending reset is confirmed`() {
        assertEquals(
            LibraryBaselineReadiness.Confirmed,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = null,
                evidence = LibraryBaselineEvidence(accountA, confirmedAt, LibraryBaselineProvenance.LOCAL),
            ),
        )
    }

    @Test
    fun `a confirmed empty library still confirms readiness`() {
        // Readiness is about account-scoped evidence, not about the presence of game rows: the
        // zero-game baseline committed exactly like a populated one.
        assertEquals(
            LibraryBaselineReadiness.Confirmed,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = null,
                evidence = LibraryBaselineEvidence(accountA, confirmedAt, LibraryBaselineProvenance.LOCAL),
            ),
        )
    }

    @Test
    fun `a generic last sync timestamp is not baseline readiness`() {
        // The profile carries steamId + lastSyncAt, but no account-scoped confirmation evidence —
        // the pre-confirmation shape a migration initializes conservatively. `lastSyncAt` alone is
        // investigation material, never readiness.
        assertEquals(
            LibraryBaselineReadiness.Unknown,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = null,
                evidence = null,
            ),
        )
    }

    @Test
    fun `restored evidence never confirms readiness`() {
        assertEquals(
            LibraryBaselineReadiness.Unknown,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = null,
                evidence = LibraryBaselineEvidence(accountA, confirmedAt, LibraryBaselineProvenance.RESTORED),
            ),
        )
    }

    @Test
    fun `legacy evidence never confirms readiness`() {
        assertEquals(
            LibraryBaselineReadiness.Unknown,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = null,
                evidence = LibraryBaselineEvidence(accountA, confirmedAt, LibraryBaselineProvenance.LEGACY),
            ),
        )
    }

    @Test
    fun `evidence confirmed for a different account does not grant readiness`() {
        assertEquals(
            LibraryBaselineReadiness.Unknown,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = null,
                evidence = LibraryBaselineEvidence(accountB, confirmedAt, LibraryBaselineProvenance.LOCAL),
            ),
        )
    }

    @Test
    fun `a pending account reset keeps readiness unknown even with matching evidence`() {
        assertEquals(
            LibraryBaselineReadiness.Unknown,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = accountA,
                evidence = LibraryBaselineEvidence(accountA, confirmedAt, LibraryBaselineProvenance.LOCAL),
            ),
        )
    }

    @Test
    fun `no active account stays unknown`() {
        assertEquals(
            LibraryBaselineReadiness.Unknown,
            repository.readinessFor(
                activeSteamId = null,
                pendingResetSteamId = null,
                evidence = LibraryBaselineEvidence(accountA, confirmedAt, LibraryBaselineProvenance.LOCAL),
            ),
        )
    }

    @Test
    fun `a later failed refresh does not revoke readiness`() {
        // A refresh failure is a poll result, not a readiness fact: the failed poll must not even
        // be able to remove an already-committed baseline.
        assertEquals(
            LibraryPollResult.RecoverableFailure("offline"),
            repository.resultFor(
                request(),
                LibraryPollEvidence.Failed(manualWork, accountA, "offline"),
            ),
        )
        assertEquals(
            LibraryBaselineReadiness.Confirmed,
            repository.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = null,
                evidence = LibraryBaselineEvidence(accountA, confirmedAt, LibraryBaselineProvenance.LOCAL),
            ),
        )
    }
}
