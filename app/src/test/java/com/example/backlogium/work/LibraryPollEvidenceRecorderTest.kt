package com.example.backlogium.work

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.repo.LibraryPollRefusal
import com.example.backlogium.data.repo.LibraryPollRepository
import com.example.backlogium.data.repo.LibraryPollRequest
import com.example.backlogium.data.repo.toBoundaryEvidence
import com.example.backlogium.domain.LibraryPollResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The production evidence seam [LibraryPollEvidenceRecorder] — the exact class
 * [SteamSyncWorker] uses — against the real Room store:
 *
 * - a committed record is authoritative and is never regressed by a later failure or no-op of the
 *   same work (the crash-after-raw-commit / retry-failure shape), and
 * - the store stays bounded at **one latest record per (work, account)** no matter how many
 *   outcomes are recorded (latest-win retention, never per-poll telemetry).
 */
@RunWith(RobolectricTestRunner::class)
class LibraryPollEvidenceRecorderTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var recorder: LibraryPollEvidenceRecorder

    private val work = "00000000-0000-0000-0000-000000000001"
    private val workB = "00000000-0000-0000-0000-000000000002"
    private val accountA = "76561198000000000"
    private val accountB = "76561198000000001"
    private val at = 1_700_000_000_000L
    private val mapper = LibraryPollRepository()

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

    private fun request(workId: String = work, account: String = accountA) =
        LibraryPollRequest(workIdentity = workId, accountSteamId = account)

    private suspend fun latest(workId: String = work, account: String = accountA) =
        database.libraryPollEvidenceDao().getFor(workId, account)

    private suspend fun resultOf(workId: String = work, account: String = accountA): LibraryPollResult =
        mapper.resultFor(request(workId, account), latest(workId, account)?.toBoundaryEvidence())

    // ----------------------------------------------------- durable committed protection

    @Test
    fun `a failure after a committed record is refused and the committed record stays authoritative`() = runBlocking {
        recorder.recordCommitted(work, accountA, gameCount = 137, committedAt = at)

        // Crash-after-raw-commit, then a retry of the SAME work identity fails before its own
        // commit: the durable record — not any run-local state — keeps the committed effect.
        val recorded = recorder.recordFailure(work, accountA, reason = "offline", recordedAt = at + 1)

        assertFalse("the committed record must refuse the later failure", recorded)
        assertEquals(LibraryPollOutcomeKind.COMMITTED.name, latest()?.outcome)
        assertEquals(137, latest()?.gameCount)
        assertEquals(LibraryPollResult.Committed(gameCount = 137, lastSyncAt = at), resultOf())
    }

    @Test
    fun `a not performed outcome after a committed record cannot regress it`() = runBlocking {
        recorder.recordCommitted(work, accountA, gameCount = 0, committedAt = at)

        // The same periodic work later sees a private/unconfirmed-empty response: the committed
        // empty baseline stays the authoritative latest record.
        val recorded = recorder.recordNotPerformed(
            work,
            accountA,
            LibraryPollRefusal.UNCONFIRMED_EMPTY,
            recordedAt = at + 1,
        )

        assertFalse(recorded)
        assertEquals(LibraryPollOutcomeKind.COMMITTED.name, latest()?.outcome)
        val result = resultOf()
        assertTrue(result is LibraryPollResult.Committed)
        assertTrue((result as LibraryPollResult.Committed).confirmedEmpty)
    }

    @Test
    fun `a failure before any commit records a recoverable failure`() = runBlocking {
        val recorded = recorder.recordFailure(work, accountA, reason = "Network request timed out", recordedAt = at)

        assertTrue(recorded)
        assertEquals(LibraryPollOutcomeKind.FAILED.name, latest()?.outcome)
        assertEquals(LibraryPollResult.RecoverableFailure("Network request timed out"), resultOf())
    }

    @Test
    fun `missing credentials and admission refusals record attributable not-performed outcomes`() = runBlocking {
        assertTrue(
            recorder.recordNotPerformed(work, accountA, LibraryPollRefusal.MISSING_CREDENTIALS, at),
        )
        assertEquals(LibraryPollResult.NotPerformed.MissingCredentials, resultOf())

        assertTrue(
            recorder.recordNotPerformed(workB, accountA, LibraryPollRefusal.ACCOUNT_ADMISSION_REFUSED, at),
        )
        assertEquals(
            LibraryPollResult.NotPerformed.AccountAdmissionRefused,
            resultOf(workB),
        )
    }

    // ------------------------------------------------------------- bounded retention

    @Test
    fun `many outcomes for one pair collapse to the single latest record`() = runBlocking {
        repeat(20) { attempt ->
            recorder.recordFailure(work, accountA, reason = "attempt $attempt", recordedAt = at + attempt)
        }

        val rows = database.libraryPollEvidenceDao().listForAccount(accountA)
        assertEquals("latest-per-pair stays bounded, never per-poll telemetry", 1, rows.size)
        assertEquals("attempt 19", rows.single().reason)
    }

    @Test
    fun `a newer commit replaces an older commit for the same pair`() = runBlocking {
        recorder.recordCommitted(work, accountA, gameCount = 3, committedAt = at)
        recorder.recordCommitted(work, accountA, gameCount = 5, committedAt = at + 1)

        val rows = database.libraryPollEvidenceDao().listForAccount(accountA)
        assertEquals(1, rows.size)
        assertEquals(5, rows.single().gameCount)
        assertEquals(LibraryPollResult.Committed(gameCount = 5, lastSyncAt = at + 1), resultOf())
    }

    @Test
    fun `distinct works and accounts keep separate rows`() = runBlocking {
        recorder.recordCommitted(work, accountA, gameCount = 2, committedAt = at)
        recorder.recordCommitted(workB, accountA, gameCount = 0, committedAt = at)
        recorder.recordCommitted(work, accountB, gameCount = 7, committedAt = at)

        assertEquals(2, database.libraryPollEvidenceDao().listForAccount(accountA).size)
        assertEquals(1, database.libraryPollEvidenceDao().listForAccount(accountB).size)
        assertEquals(LibraryPollResult.Committed(gameCount = 2, lastSyncAt = at), resultOf(work, accountA))
        assertEquals(LibraryPollResult.Committed(gameCount = 7, lastSyncAt = at), resultOf(work, accountB))
    }

    @Test
    fun `a committed record written inside a rejected transaction rolls back with it`() = runBlocking {
        val thrown = runCatching {
            database.withTransaction {
                recorder.recordCommitted(work, accountA, gameCount = 1, committedAt = at)
                error("simulated crash before the raw commit completes")
            }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)

        assertTrue("an interrupted initial commit leaves no separate evidence", latest() == null)
        assertEquals(LibraryPollResult.Unknown, resultOf())
    }

    // ------------------------------------------------------------ bounded retention

    private fun workIds(count: Int): List<String> = (1..count).map { index ->
        "00000000-0000-0000-0000-%012d".format(index)
    }

    @Test
    fun `prune keeps the current work, protected associations and the newest outcomes`() = runBlocking {
        // 6 terminal outcomes for 6 distinct work identities, oldest first — repeated manual jobs.
        val works = workIds(6)
        works.forEachIndexed { index, workId ->
            recorder.recordFailure(workId, accountA, reason = "attempt", recordedAt = at + index)
        }
        val protected = works[0] // the setup store is still observing this stage's association
        val current = works[5]   // the work this attempt just ran

        val pruned = recorder.pruneOlderThanRetained(
            accountSteamId = accountA,
            currentWorkId = current,
            protectedWorkIds = setOf(protected),
            retainedCount = 2,
        )

        // Kept: protected (even though it is the oldest), current, and the newest 2 others.
        val kept = database.libraryPollEvidenceDao().listForAccount(accountA).map { it.workIdentity }.toSet()
        assertTrue("a protected association is never pruned", kept.contains(protected))
        assertTrue("the current work is never pruned", kept.contains(current))
        assertEquals(setOf(protected, current, works[3], works[4]), kept)
        assertEquals("prune returns the number of removed terminal rows", 2, pruned)
    }

    @Test
    fun `prune bounds repeated manual jobs to the newest outcomes per account`() = runBlocking {
        val works = workIds(9)
        works.forEachIndexed { index, workId ->
            recorder.recordCommitted(workId, accountA, gameCount = index, committedAt = at + index)
        }

        val pruned = recorder.pruneOlderThanRetained(
            accountSteamId = accountA,
            currentWorkId = works[8],
            protectedWorkIds = emptySet(),
            retainedCount = 3,
        )

        val kept = database.libraryPollEvidenceDao().listForAccount(accountA).map { it.workIdentity }.toSet()
        assertTrue("the current (most recent) work survives", kept.contains(works[8]))
        assertEquals(setOf(works[5], works[6], works[7], works[8]), kept)
        assertEquals(5, pruned)
    }

    @Test
    fun `prune is scoped to one account`() = runBlocking {
        repeat(4) { index ->
            recorder.recordCommitted(workIds(4)[index], accountA, gameCount = 1, committedAt = at + index)
            recorder.recordCommitted(workIds(4)[index], accountB, gameCount = 1, committedAt = at + index)
        }
        val current = workIds(4)[3]

        recorder.pruneOlderThanRetained(accountSteamId = accountA, currentWorkId = current, protectedWorkIds = emptySet(), retainedCount = 1)

        assertEquals(2, database.libraryPollEvidenceDao().listForAccount(accountA).size)
        assertEquals("another account's outcomes are untouched", 4, database.libraryPollEvidenceDao().listForAccount(accountB).size)
    }

    @Test
    fun `prune never prunes the current or a protected work even at the tightest cap`() = runBlocking {
        val works = workIds(3)
        works.forEachIndexed { index, workId ->
            recorder.recordNotPerformed(workId, accountA, LibraryPollRefusal.UNCONFIRMED_EMPTY, at + index)
        }

        recorder.pruneOlderThanRetained(
            accountSteamId = accountA,
            currentWorkId = works[0],
            protectedWorkIds = setOf(works[2]),
            retainedCount = 0,
        )

        val kept = database.libraryPollEvidenceDao().listForAccount(accountA).map { it.workIdentity }.toSet()
        assertEquals(setOf(works[0], works[2]), kept)
    }
}