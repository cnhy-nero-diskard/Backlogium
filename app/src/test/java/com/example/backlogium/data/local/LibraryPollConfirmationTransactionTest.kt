package com.example.backlogium.data.local

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.repo.LibraryPollRepository
import com.example.backlogium.data.repo.LibraryPollRefusal
import com.example.backlogium.data.repo.LibraryPollRequest
import com.example.backlogium.data.repo.toBaselineEvidence
import com.example.backlogium.data.repo.toBoundaryEvidence
import com.example.backlogium.domain.LibraryBaselineReadiness
import com.example.backlogium.domain.LibraryPollResult
import com.example.backlogium.work.LibraryPollEvidenceRecorder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Task 2.3 real-database coverage driven through the **production seam** the worker itself uses:
 * [LibraryPollEvidenceRecorder.recordCommitted] is called inside the same Room transaction that
 * commits the accepted library, [LibraryPollEvidenceRecorder.recordNotPerformed] covers the
 * rejected/private (unconfirmed empty) response, and [LibraryPollEvidenceRecorder.recordFailure]
 * cannot regress a committed record when a later refresh fails. Interrupted commits roll back
 * confirmation, evidence, and library together; account reset invalidates readiness.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryPollConfirmationTransactionTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var recorder: LibraryPollEvidenceRecorder

    private val accountA = "76561198000000000"
    private val accountB = "76561198000000001"
    private val work = "00000000-0000-0000-0000-000000000001"
    private val now = 1_700_000_000_000L
    private val pollMapper = LibraryPollRepository()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        recorder = LibraryPollEvidenceRecorder(database.libraryPollEvidenceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun game(appId: Long = 440L) = Game(
        appId = appId,
        name = "Game",
        iconUrl = "icon",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
        lastSyncedAt = now,
    )

    /** The accepted-raw-transaction shape, using the worker's own evidence seam. */
    private suspend fun commitAcceptedPoll(gameCount: Int, appId: Long = 440L) {
        database.withTransaction {
            database.playerProfileDao().insertIfMissing()
            if (gameCount > 0) {
                database.gameDao().upsert(game(appId))
            }
            database.playerProfileDao().updateSyncStatus(lastSyncAt = now, lastSyncError = null)
            recorder.recordCommitted(
                workIdentity = work,
                accountSteamId = accountA,
                gameCount = gameCount,
                committedAt = now,
            )
            database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = now)
        }
    }

    private suspend fun readiness(): LibraryBaselineReadiness =
        pollMapper.readinessFor(
            activeSteamId = accountA,
            pendingResetSteamId = null,
            evidence = database.playerProfileDao().get()?.toBaselineEvidence(),
        )

    @Test
    fun `an accepted first commit records confirmation and evidence atomically with the library`() = runBlocking {
        commitAcceptedPoll(gameCount = 1)

        assertEquals("an accepted first commit persists the library row", 440L, database.gameDao().getById(440L)?.appId)
        val profile = checkNotNull(database.playerProfileDao().get())
        assertEquals(accountA, profile.confirmedLibrarySteamId)
        assertEquals(now, profile.confirmedLibraryAt)
        val evidence = checkNotNull(database.libraryPollEvidenceDao().getFor(work, accountA))
        assertEquals(LibraryPollOutcomeKind.COMMITTED.name, evidence.outcome)
        assertEquals(1, evidence.gameCount)
        assertEquals(LibraryBaselineReadiness.Confirmed, readiness())
    }

    @Test
    fun `a confirmed empty library commits a zero-game baseline`() = runBlocking {
        // Steam explicitly confirmed `game_count = 0` and the empty list is accepted.
        commitAcceptedPoll(gameCount = 0)

        assertEquals(0, database.gameDao().count())
        val profile = checkNotNull(database.playerProfileDao().get())
        assertEquals(accountA, profile.confirmedLibrarySteamId)
        val evidence = checkNotNull(database.libraryPollEvidenceDao().getFor(work, accountA))
        assertEquals(LibraryPollOutcomeKind.COMMITTED.name, evidence.outcome)
        assertEquals(0, evidence.gameCount)
        // Zero games is a successful empty baseline, not a privacy failure or "no readiness".
        assertEquals(LibraryBaselineReadiness.Confirmed, readiness())
    }

    @Test
    fun `a rejected or private response records a not performed outcome, never readiness`() = runBlocking {
        // The unconfirmed empty / private-profile path: no committed row, no confirmation, and a
        // durable not-performed outcome for the exact work/account.
        recorder.recordNotPerformed(
            workIdentity = work,
            accountSteamId = accountA,
            refusal = LibraryPollRefusal.UNCONFIRMED_EMPTY,
            recordedAt = now,
        )

        val evidence = checkNotNull(database.libraryPollEvidenceDao().getFor(work, accountA))
        assertEquals(LibraryPollOutcomeKind.NOT_PERFORMED.name, evidence.outcome)
        assertEquals(LibraryPollRefusal.UNCONFIRMED_EMPTY.name, evidence.refusal)
        assertNull(database.playerProfileDao().get()?.confirmedLibrarySteamId)
        assertEquals(LibraryBaselineReadiness.Unknown, readiness())
        assertEquals(
            LibraryPollResult.NotPerformed.UnconfirmedEmpty,
            pollMapper.resultFor(
                LibraryPollRequest(work, accountA),
                evidence.toBoundaryEvidence(),
            ),
        )
    }

    @Test
    fun `an interrupted initial commit records neither library nor separate evidence`() = runBlocking {
        val thrown = runCatching {
            database.withTransaction {
                database.playerProfileDao().insertIfMissing()
                database.gameDao().upsert(game())
                recorder.recordCommitted(work, accountA, gameCount = 1, committedAt = now)
                database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = now)
                error("simulated crash before the raw commit completes")
            }
        }.exceptionOrNull()
        assertTrue("the transaction must abort", thrown is IllegalStateException)

        // Readiness was never recorded separately from the library data.
        assertNull(database.playerProfileDao().get())
        assertNull(database.libraryPollEvidenceDao().getFor(work, accountA))
        assertEquals(0, database.gameDao().count())
        assertEquals(LibraryBaselineReadiness.Unknown, readiness())
    }

    @Test
    fun `a later failed refresh cannot regress the committed record and retains readiness`() = runBlocking {
        commitAcceptedPoll(gameCount = 1)

        // The same work's next attempt fails before its own commit. The production recorder keeps
        // the durable COMMITTED record authoritative — the failure is a poll result, never a
        // readiness fact, and never an overwrite of the committed effect.
        val recorded = recorder.recordFailure(work, accountA, reason = "offline", recordedAt = now + 1)

        assertFalse("the durable committed record refuses the later failure", recorded)
        val evidence = checkNotNull(database.libraryPollEvidenceDao().getFor(work, accountA))
        assertEquals(LibraryPollOutcomeKind.COMMITTED.name, evidence.outcome)
        val profile = checkNotNull(database.playerProfileDao().get())
        assertEquals(accountA, profile.confirmedLibrarySteamId)
        assertEquals("a failed refresh must not revoke the previously committed baseline", LibraryBaselineReadiness.Confirmed, readiness())
    }

    @Test
    fun `account reset invalidates the previous account's readiness`() = runBlocking {
        commitAcceptedPoll(gameCount = 1)
        assertEquals(LibraryBaselineReadiness.Confirmed, readiness())

        // The reset clears the account-owned confirmation together with the rest of the profile.
        database.playerProfileDao().resetForAccountChange(accountB)

        val profile = checkNotNull(database.playerProfileDao().get())
        assertNull(profile.confirmedLibrarySteamId)
        assertNull(profile.confirmedLibraryAt)
        assertEquals(
            "readiness must never be inferred from the previous account's confirmation",
            LibraryBaselineReadiness.Unknown,
            pollMapper.readinessFor(
                activeSteamId = accountB,
                pendingResetSteamId = null,
                evidence = profile.toBaselineEvidence(),
            ),
        )
    }

    @Test
    fun `a pending account reset keeps readiness unknown even with evidence present`() = runBlocking {
        commitAcceptedPoll(gameCount = 1)

        assertEquals(
            LibraryBaselineReadiness.Unknown,
            pollMapper.readinessFor(
                activeSteamId = accountA,
                pendingResetSteamId = accountA,
                evidence = database.playerProfileDao().get()?.toBaselineEvidence(),
            ),
        )
    }
}