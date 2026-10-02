package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.LibraryPollEvidenceRecord
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.history.DataStoreHistoryImportRequestStore
import com.example.backlogium.data.history.DefaultHistoryImportAccountGateway
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.HistoryImportCoordinator
import com.example.backlogium.domain.LibraryBaselineReadiness
import com.example.backlogium.domain.LibraryPollResult
import com.example.backlogium.domain.PlaytimeBackfillUseCase
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.SteamSyncCoordinator
import com.example.backlogium.work.SyncScheduler
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Task 2.4 exposure: the injected [ProfileRepository] read entry projects durable, attributable
 * library-poll results and account-scoped baseline readiness from the real Room store — the same
 * calls the onboarding history surface and Settings will make. Missing credentials, account
 * admission refusal, privacy/unconfirmed responses, overlapping polls, and empty evidence can
 * never project to a committed library or a confirmed baseline.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryReadinessExposureTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var repository: ProfileRepository
    private lateinit var credentials: FakeCredentialsProvider
    private lateinit var marker: AccountChangeMarkerStore

    private val accountA = "76561198000000000"
    private val accountB = "76561198000000001"
    private val manualWork = "00000000-0000-0000-0000-000000000001"
    private val periodicWork = "00000000-0000-0000-0000-000000000002"
    private val confirmedAt = 1_700_000_000_000L
    private val lastSyncAt = 1_700_000_123_456L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        credentials = FakeCredentialsProvider(accountA)
        marker = AccountChangeMarkerStore(context)
        runBlocking { marker.clear() }
        val time = FixedTime()
        val profileDao = database.playerProfileDao()
        val gameDao = database.gameDao()
        val sessionDao = database.sessionDao()
        val dailyProgressDao = database.dailyProgressDao()
        val updater = GamificationUpdater(
            sessionDao, dailyProgressDao, profileDao, database.hltbDataDao(),
            database.achievementDao(), gameDao, database.hiddenGameDao(),
        )
        val derived = DerivedStateWriteCoordinator()
        val backfill = PlaytimeBackfillUseCase(
            gameDao = gameDao,
            sessionDao = sessionDao,
            playerProfileDao = profileDao,
            settings = SettingsDataStore(context),
            gamificationUpdater = updater,
            time = time,
            derivedStateWrites = derived,
            transaction = com.example.backlogium.data.backup.RoomDatabaseTransactionScope(database),
            syncCoordinator = SteamSyncCoordinator(),
            account = DefaultHistoryImportAccountGateway(credentials, marker),
        )
        val historyImport = HistoryImportCoordinator(
            requestStore = DataStoreHistoryImportRequestStore(context),
            backfill = backfill,
            account = DefaultHistoryImportAccountGateway(credentials, marker),
            time = time,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        repository = ProfileRepository(
            profileDao = profileDao,
            dailyProgressDao = dailyProgressDao,
            syncScheduler = SyncScheduler(context, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)),
            historyImport = historyImport,
            credentials = credentials,
            accountChangeMarker = marker,
            evidenceDao = database.libraryPollEvidenceDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun request(work: String = manualWork, account: String = accountA) =
        LibraryPollRequest(workIdentity = work, accountSteamId = account)

    private suspend fun record(row: LibraryPollEvidenceRecord) =
        database.libraryPollEvidenceDao().upsert(row)

    private suspend fun committed(gameCount: Int, work: String = manualWork) = record(
        LibraryPollEvidenceRecord(
            workIdentity = work,
            accountSteamId = accountA,
            outcome = LibraryPollOutcomeKind.COMMITTED.name,
            gameCount = gameCount,
            lastSyncAt = lastSyncAt,
            refusal = null,
            reason = null,
            recordedAt = confirmedAt,
        ),
    )

    // ------------------------------------------------------- attributable results

    @Test
    fun `a committed poll is projected as committed for exactly that request`() = runBlocking {
        committed(gameCount = 137)
        assertEquals(
            LibraryPollResult.Committed(gameCount = 137, lastSyncAt = lastSyncAt),
            repository.libraryPollResult(request()),
        )
    }

    @Test
    fun `a confirmed empty library is projected as a committed empty baseline`() = runBlocking {
        committed(gameCount = 0)
        val result = repository.libraryPollResult(request())
        assertTrue(result is LibraryPollResult.Committed)
        assertTrue((result as LibraryPollResult.Committed).confirmedEmpty)
    }

    @Test
    fun `a missing-credentials outcome is not performed, never committed`() = runBlocking {
        record(
            LibraryPollEvidenceRecord(
                workIdentity = manualWork,
                accountSteamId = accountA,
                outcome = LibraryPollOutcomeKind.NOT_PERFORMED.name,
                gameCount = -1,
                lastSyncAt = 0L,
                refusal = LibraryPollRefusal.MISSING_CREDENTIALS.name,
                reason = null,
                recordedAt = confirmedAt,
            ),
        )
        assertEquals(
            LibraryPollResult.NotPerformed.MissingCredentials,
            repository.libraryPollResult(request()),
        )
    }

    @Test
    fun `an account-admission refusal is not performed, never committed`() = runBlocking {
        record(
            LibraryPollEvidenceRecord(
                workIdentity = manualWork,
                accountSteamId = accountA,
                outcome = LibraryPollOutcomeKind.NOT_PERFORMED.name,
                gameCount = -1,
                lastSyncAt = 0L,
                refusal = LibraryPollRefusal.ACCOUNT_ADMISSION_REFUSED.name,
                reason = null,
                recordedAt = confirmedAt,
            ),
        )
        assertEquals(
            LibraryPollResult.NotPerformed.AccountAdmissionRefused,
            repository.libraryPollResult(request()),
        )
    }

    @Test
    fun `a privacy-unconfirmed empty response is not performed and not a success`() = runBlocking {
        record(
            LibraryPollEvidenceRecord(
                workIdentity = manualWork,
                accountSteamId = accountA,
                outcome = LibraryPollOutcomeKind.NOT_PERFORMED.name,
                gameCount = -1,
                lastSyncAt = 0L,
                refusal = LibraryPollRefusal.UNCONFIRMED_EMPTY.name,
                reason = null,
                recordedAt = confirmedAt,
            ),
        )
        assertEquals(
            LibraryPollResult.NotPerformed.UnconfirmedEmpty,
            repository.libraryPollResult(request()),
        )
    }

    @Test
    fun `a failed poll is a recoverable failure with its reason`() = runBlocking {
        record(
            LibraryPollEvidenceRecord(
                workIdentity = manualWork,
                accountSteamId = accountA,
                outcome = LibraryPollOutcomeKind.FAILED.name,
                gameCount = -1,
                lastSyncAt = 0L,
                refusal = null,
                reason = "Couldn't reach Steam",
                recordedAt = confirmedAt,
            ),
        )
        assertEquals(
            LibraryPollResult.RecoverableFailure("Couldn't reach Steam"),
            repository.libraryPollResult(request()),
        )
    }

    @Test
    fun `no evidence at all is unknown, never a committed library`() = runBlocking {
        assertEquals(LibraryPollResult.Unknown, repository.libraryPollResult(request()))
    }

    @Test
    fun `another account's committed evidence cannot project onto this request`() = runBlocking {
        record(
            LibraryPollEvidenceRecord(
                workIdentity = manualWork,
                accountSteamId = accountB,
                outcome = LibraryPollOutcomeKind.COMMITTED.name,
                gameCount = 137,
                lastSyncAt = lastSyncAt,
                refusal = null,
                reason = null,
                recordedAt = confirmedAt,
            ),
        )
        assertEquals(LibraryPollResult.Unknown, repository.libraryPollResult(request()))
    }

    @Test
    fun `overlapping manual and periodic polls each project their own operation`() = runBlocking {
        committed(gameCount = 137, work = manualWork)
        committed(gameCount = 0, work = periodicWork)

        assertEquals(
            LibraryPollResult.Committed(gameCount = 137, lastSyncAt = lastSyncAt),
            repository.libraryPollResult(request(work = manualWork)),
        )
        val periodic = repository.libraryPollResult(request(work = periodicWork))
        assertTrue(periodic is LibraryPollResult.Committed)
        assertTrue((periodic as LibraryPollResult.Committed).confirmedEmpty)
        // Overlap alone is not failure and never confuses one operation with the other.
        assertFalse(periodic.gameCount == 137)
    }

    // ---------------------------------------------------------------- readiness

    @Test
    fun `readiness is unknown while no active account is configured`() = runBlocking {
        credentials.steamId = null
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = confirmedAt)

        assertEquals(LibraryBaselineReadiness.Unknown, repository.libraryBaselineReadiness())
    }

    @Test
    fun `a local same-account confirmation confirms readiness`() = runBlocking {
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = confirmedAt)

        assertEquals(LibraryBaselineReadiness.Confirmed, repository.libraryBaselineReadiness())
    }

    @Test
    fun `a generic lastSyncAt without confirmation leaves readiness unknown`() = runBlocking {
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateSyncStatus(lastSyncAt = lastSyncAt, lastSyncError = null)

        assertEquals(LibraryBaselineReadiness.Unknown, repository.libraryBaselineReadiness())
    }

    @Test
    fun `a pending account reset keeps readiness unknown despite matching confirmation`() = runBlocking {
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = confirmedAt)
        marker.markPending(accountA)

        assertEquals(LibraryBaselineReadiness.Unknown, repository.libraryBaselineReadiness())
    }

    @Test
    fun `after an account change readiness is unknown for the new account`() = runBlocking {
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateLibraryConfirmation(steamId = accountA, confirmedAt = confirmedAt)
        assertEquals(LibraryBaselineReadiness.Confirmed, repository.libraryBaselineReadiness())

        // The account boundary clears the old account's confirmation.
        database.playerProfileDao().resetForAccountChange(accountB)
        credentials.steamId = accountB

        assertEquals(
            "the replacement account never inherits readiness from the previous account",
            LibraryBaselineReadiness.Unknown,
            repository.libraryBaselineReadiness(),
        )
    }

    private class FakeCredentialsProvider(var steamId: String?) : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured? =
            steamId?.let { CredentialsState.Configured(apiKey = "key", steamId = it) }
    }

    private class FixedTime : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-10-02")
    }
}
