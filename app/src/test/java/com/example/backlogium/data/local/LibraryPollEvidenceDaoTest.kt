package com.example.backlogium.data.local

import androidx.room.Room
import com.example.backlogium.data.local.dao.LibraryPollEvidenceDao
import com.example.backlogium.data.local.entity.LibraryPollEvidenceRecord
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The durable, attributable library-poll evidence store (stabilize-first-run-setup): latest-wins
 * per exact work id + account, account scoping of every row, and the account-wide purge.
 *
 * The load-bearing shape is the composite primary key: two accounts never share a row, so an old
 * account's outcome can never be projected onto a replacement account's request, and a later
 * attempt of the same work supersedes the previous attempt's row under the same key.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryPollEvidenceDaoTest {

    private lateinit var database: BacklogiumDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val dao: LibraryPollEvidenceDao
        get() = database.libraryPollEvidenceDao()

    private fun row(
        work: String = "00000000-0000-0000-0000-000000000001",
        account: String = "76561198000000000",
        outcome: String = LibraryPollOutcomeKind.COMMITTED.name,
        gameCount: Int = 3,
        lastSyncAt: Long = 1_000L,
        refusal: String? = null,
        reason: String? = null,
        recordedAt: Long = 1_000L,
    ) = LibraryPollEvidenceRecord(
        workIdentity = work,
        accountSteamId = account,
        outcome = outcome,
        gameCount = gameCount,
        lastSyncAt = lastSyncAt,
        refusal = refusal,
        reason = reason,
        recordedAt = recordedAt,
    )

    @Test
    fun `a committed outcome round-trips for its exact work and account`() = runBlocking {
        val stored = row(gameCount = 137, lastSyncAt = 1_700_000_123_456L)
        dao.upsert(stored)

        assertEquals(stored, dao.getFor("00000000-0000-0000-0000-000000000001", "76561198000000000"))
    }

    /**
     * The row's storage semantics: an upsert replaces the previous row under the same pair. The
     * *policy* deciding whether a given outcome may overwrite an existing one lives in
     * [com.example.backlogium.work.LibraryPollEvidenceRecorder] — which refuses to regress a
     * COMMITTED record — so this is deliberately a storage-level test, not a policy claim.
     */
    @Test
    fun `a later attempt of the same work and account supersedes the older outcome at storage level`() = runBlocking {
        dao.upsert(row(outcome = LibraryPollOutcomeKind.COMMITTED.name, gameCount = 137, recordedAt = 1_000L))
        // The same periodic work later fails before its commit: latest outcome wins per pair.
        dao.upsert(
            row(
                outcome = LibraryPollOutcomeKind.FAILED.name,
                gameCount = -1,
                recordedAt = 2_000L,
                reason = "Network request timed out",
            ),
        )

        val latest = checkNotNull(dao.getFor("00000000-0000-0000-0000-000000000001", "76561198000000000"))
        assertEquals(LibraryPollOutcomeKind.FAILED.name, latest.outcome)
        assertEquals("Network request timed out", latest.reason)
        assertEquals(2_000L, latest.recordedAt)
    }

    @Test
    fun `two accounts keep separate rows under the same periodic work identity`() = runBlocking {
        dao.upsert(row(account = "76561198000000000", outcome = LibraryPollOutcomeKind.COMMITTED.name))
        dao.upsert(
            row(
                account = "76561198000000001",
                work = "00000000-0000-0000-0000-000000000001",
                outcome = LibraryPollOutcomeKind.COMMITTED.name,
            ),
        )

        val accountA = dao.getFor("00000000-0000-0000-0000-000000000001", "76561198000000000")
        val accountB = dao.getFor("00000000-0000-0000-0000-000000000001", "76561198000000001")
        assertEquals("76561198000000000", accountA?.accountSteamId)
        assertEquals("76561198000000001", accountB?.accountSteamId)
        assertTrue("distinct accounts never collide on the same work id", accountA != accountB)
    }

    @Test
    fun `a different admitted work can never read another work's outcome`() = runBlocking {
        dao.upsert(row(work = "00000000-0000-0000-0000-000000000001"))

        assertNull(
            "a brand-new manual request under the same unique name is a different work identity",
            dao.getFor("00000000-0000-0000-0000-000000000009", "76561198000000000"),
        )
    }

    @Test
    fun `the observable latest outcome emits on the exact pair`() = runBlocking {
        dao.upsert(row(outcome = LibraryPollOutcomeKind.NOT_PERFORMED.name, refusal = "UNCONFIRMED_EMPTY"))

        val observed = dao.observeFor("00000000-0000-0000-0000-000000000001", "76561198000000000").first()
        assertEquals(LibraryPollOutcomeKind.NOT_PERFORMED.name, observed?.outcome)
        assertEquals("UNCONFIRMED_EMPTY", observed?.refusal)
    }

    @Test
    fun `account listing is newest first`() = runBlocking {
        dao.upsert(row(recordedAt = 1_000L, outcome = LibraryPollOutcomeKind.COMMITTED.name))
        dao.upsert(row(work = "00000000-0000-0000-0000-000000000002", recordedAt = 3_000L, outcome = LibraryPollOutcomeKind.FAILED.name, reason = "boom"))
        dao.upsert(row(work = "00000000-0000-0000-0000-000000000003", recordedAt = 2_000L, outcome = LibraryPollOutcomeKind.NOT_PERFORMED.name, refusal = "UNCONFIRMED_EMPTY"))

        val rows = dao.listForAccount("76561198000000000")
        assertEquals(listOf(3_000L, 2_000L, 1_000L), rows.map { it.recordedAt })
    }

    @Test
    fun `account purge discards every recorded outcome`() = runBlocking {
        dao.upsert(row(account = "76561198000000000"))
        dao.upsert(row(account = "76561198000000001"))
        assertEquals(2, dao.listForAccount("76561198000000000").size + dao.listForAccount("76561198000000001").size)

        dao.deleteAll()

        assertNull(
            dao.getFor("00000000-0000-0000-0000-000000000001", "76561198000000000"),
        )
        assertNull(
            dao.getFor("00000000-0000-0000-0000-000000000001", "76561198000000001"),
        )
    }
}
