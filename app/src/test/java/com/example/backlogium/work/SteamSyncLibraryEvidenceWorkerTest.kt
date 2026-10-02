package com.example.backlogium.work

import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.backlogium.data.backup.AutoSnapshotWriter
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.PresenceDecisionRecorder
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGameDto
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResult
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.data.remote.dto.SteamLevelResult
import com.example.backlogium.data.remote.dto.StoreItemsResponse
import com.example.backlogium.data.remote.dto.WishlistResponse
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CloudPendingEvidencePruner
import com.example.backlogium.data.repo.CloudPresencePlacementReader
import com.example.backlogium.data.repo.CloudReaderStateMutex
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.LibraryPollRepository
import com.example.backlogium.data.repo.LibraryPollRequest
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.data.repo.toBaselineEvidence
import com.example.backlogium.data.repo.toBoundaryEvidence
import com.example.backlogium.data.setup.DataStoreSetupStateStore
import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.data.setup.StageAttemptRecord
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.LibraryBaselineReadiness
import com.example.backlogium.domain.LibraryPollResult
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.SharedGameConverter
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.setup.AdmissionKind
import com.example.backlogium.work.setup.SetupOperationState
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
 * Drives the real [SteamSyncWorker.doWork] — real Room database, real committer, real gamification
 * recompute, real diagnostics — over a fake [SteamApi], a fake credential provider, and the narrow
 * [AutoSnapshotWriter] gateway (the only seam extracted for host-testability). Steam requests and
 * the backup snapshot are the only substitutes; every evidence row, confirmation write, rollback
 * decision, and work/account attribution is the production path.
 *
 * Covers the 2.3/2.4 attribution surface through the worker: confirmed empty baseline, unconfirmed
 * empty / private response, missing credentials, account-admission refusal (marker and stored
 * mismatch), a recoverable failure before any commit, and — with no run-local flag involved — a
 * committed effect surviving a later retry failure of the same work identity.
 */
@RunWith(RobolectricTestRunner::class)
class SteamSyncLibraryEvidenceWorkerTest {

    private lateinit var context: Context
    private lateinit var db: BacklogiumDatabase
    private lateinit var steamApi: FakeSteamApi
    private lateinit var credentials: FakeCredentialsProvider
    private lateinit var marker: AccountChangeMarkerStore
    private lateinit var time: MutableTime
    private lateinit var snapshots: FakeAutoSnapshotWriter
    private lateinit var evidenceRecorder: LibraryPollEvidenceRecorder
    private lateinit var setupStore: SetupStateStore

    private val accountA = "76561198000000000"
    private val accountB = "76561198000000001"
    private val pollMapper = LibraryPollRepository()
    private val syncCoordinator = SteamSyncCoordinator()
    private val derivedStateWrites = DerivedStateWriteCoordinator()
    private lateinit var updater: GamificationUpdater

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        steamApi = FakeSteamApi()
        credentials = FakeCredentialsProvider(accountA)
        marker = AccountChangeMarkerStore(context)
        runBlocking { marker.clear() }
        time = MutableTime()
        snapshots = FakeAutoSnapshotWriter()
        evidenceRecorder = LibraryPollEvidenceRecorder(db.libraryPollEvidenceDao())
        setupStore = DataStoreSetupStateStore(context)
        updater = GamificationUpdater(db.sessionDao(), db.dailyProgressDao(), db.playerProfileDao(),
            db.hltbDataDao(), db.achievementDao(), db.gameDao(), db.hiddenGameDao())
    }

    @After
    fun tearDown() {
        runBlocking {
            marker.clear()
            setupStore.removeAttempt("library_sync")
        }
        db.close()
    }

    @Test
    fun `a confirmed empty library commits durable evidence and confirmation through the worker`() = runBlocking {
        steamApi.owned = OwnedGamesResponse(OwnedGamesResult(gameCount = 0, games = emptyList()))
        val workId = UUID.randomUUID()

        val result = buildWorker(workId, SteamSyncWorker.TRIGGER_MANUAL).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val row = checkNotNull(db.libraryPollEvidenceDao().getFor(workId.toString(), accountA))
        assertEquals(LibraryPollOutcomeKind.COMMITTED.name, row.outcome)
        assertEquals(0, row.gameCount)
        assertEquals(accountA, checkNotNull(db.playerProfileDao().get()).confirmedLibrarySteamId)
        val projected = pollMapper.resultFor(
            LibraryPollRequest(workId.toString(), accountA),
            row.toBoundaryEvidence(),
        )
        assertTrue("a confirmed empty baseline is a committed success", projected is LibraryPollResult.Committed)
        assertTrue((projected as LibraryPollResult.Committed).confirmedEmpty)
        assertEquals(LibraryBaselineReadiness.Confirmed, readiness())
    }

    @Test
    fun `an unconfirmed empty response is a private-profile no-op, never a committed library`() = runBlocking {
        // Empty envelope with no explicit `game_count`: Steam did not confirm the zero.
        steamApi.owned = OwnedGamesResponse(OwnedGamesResult(gameCount = null, games = emptyList()))
        val workId = UUID.randomUUID()

        val result = buildWorker(workId, SteamSyncWorker.TRIGGER_PERIODIC).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNull("no commit means no baseline confirmation", db.playerProfileDao().get()?.confirmedLibrarySteamId)
        val row = checkNotNull(db.libraryPollEvidenceDao().getFor(workId.toString(), accountA))
        assertEquals(LibraryPollOutcomeKind.NOT_PERFORMED.name, row.outcome)
        assertEquals(
            LibraryPollResult.NotPerformed.UnconfirmedEmpty,
            pollMapper.resultFor(LibraryPollRequest(workId.toString(), accountA), row.toBoundaryEvidence()),
        )
        assertEquals(LibraryBaselineReadiness.Unknown, readiness())
    }

    @Test
    fun `missing credentials produce an attributable not performed outcome`() = runBlocking {
        seedProfileAccount(accountA)
        credentials.steamId = null
        val workId = UUID.randomUUID()

        val result = buildWorker(workId, SteamSyncWorker.TRIGGER_PERIODIC).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val row = checkNotNull(db.libraryPollEvidenceDao().getFor(workId.toString(), accountA))
        assertEquals(LibraryPollOutcomeKind.NOT_PERFORMED.name, row.outcome)
        assertEquals(
            LibraryPollResult.NotPerformed.MissingCredentials,
            pollMapper.resultFor(LibraryPollRequest(workId.toString(), accountA), row.toBoundaryEvidence()),
        )
    }

    @Test
    fun `an account admission refusal is attributable and never a committed library`() = runBlocking {
        seedProfileAccount(accountA)
        marker.markPending(accountB)
        val workId = UUID.randomUUID()

        val result = buildWorker(workId, SteamSyncWorker.TRIGGER_MANUAL).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val row = checkNotNull(db.libraryPollEvidenceDao().getFor(workId.toString(), accountA))
        assertEquals(LibraryPollOutcomeKind.NOT_PERFORMED.name, row.outcome)
        assertEquals(
            LibraryPollResult.NotPerformed.AccountAdmissionRefused,
            pollMapper.resultFor(LibraryPollRequest(workId.toString(), accountA), row.toBoundaryEvidence()),
        )
    }

    @Test
    fun `a stored-library account mismatch is refused at the boundary`() = runBlocking {
        seedProfileAccount(accountB)
        val workId = UUID.randomUUID()

        val result = buildWorker(workId, SteamSyncWorker.TRIGGER_MANUAL).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val row = checkNotNull(db.libraryPollEvidenceDao().getFor(workId.toString(), accountA))
        assertEquals(LibraryPollOutcomeKind.NOT_PERFORMED.name, row.outcome)
        assertEquals(
            LibraryPollResult.NotPerformed.AccountAdmissionRefused,
            pollMapper.resultFor(LibraryPollRequest(workId.toString(), accountA), row.toBoundaryEvidence()),
        )
        assertNull("a refused poll never confirms readiness", db.playerProfileDao().get()?.confirmedLibrarySteamId)
    }

    @Test
    fun `a network failure before any commit records a recoverable failure, no false success`() = runBlocking {
        steamApi.failOwned = RuntimeException("Network request timed out")
        val workId = UUID.randomUUID()

        val result = buildWorker(workId, SteamSyncWorker.TRIGGER_PERIODIC).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        val row = checkNotNull(db.libraryPollEvidenceDao().getFor(workId.toString(), accountA))
        assertEquals(LibraryPollOutcomeKind.FAILED.name, row.outcome)
        assertEquals("Network request timed out", row.reason)
        assertEquals(
            LibraryPollResult.RecoverableFailure("Network request timed out"),
            pollMapper.resultFor(LibraryPollRequest(workId.toString(), accountA), row.toBoundaryEvidence()),
        )
        assertNull(db.playerProfileDao().get()?.confirmedLibrarySteamId)
        assertEquals(LibraryBaselineReadiness.Unknown, readiness())
    }

    @Test
    fun `a committed effect survives a later retry failure of the same work`() = runBlocking {
        steamApi.owned = OwnedGamesResponse(
            OwnedGamesResult(
                gameCount = 1,
                games = listOf(OwnedGameDto(appid = 440L, name = "Portal")),
            ),
        )
        val workId = UUID.randomUUID()

        // Attempt 1 commits: durable COMMITTED evidence + confirmation inside the raw transaction.
        assertTrue(buildWorker(workId, SteamSyncWorker.TRIGGER_MANUAL).doWork() is ListenableWorker.Result.Success)
        assertEquals(accountA, checkNotNull(db.playerProfileDao().get()).confirmedLibrarySteamId)

        // Attempt 2 — the same work identity, WorkManager retry — crashes before its own commit:
        // the durable COMMITTED record (not any run-local flag) keeps the accepted effect.
        steamApi.failOwned = RuntimeException("crash after the raw commit")
        assertTrue(buildWorker(workId, SteamSyncWorker.TRIGGER_MANUAL).doWork() is ListenableWorker.Result.Retry)

        val row = checkNotNull(db.libraryPollEvidenceDao().getFor(workId.toString(), accountA))
        assertEquals(
            "the committed record is authoritative across attempts",
            LibraryPollOutcomeKind.COMMITTED.name,
            row.outcome,
        )
        assertEquals(1, row.gameCount)
        assertEquals(
            LibraryPollResult.Committed(gameCount = 1, lastSyncAt = time.now),
            pollMapper.resultFor(LibraryPollRequest(workId.toString(), accountA), row.toBoundaryEvidence()),
        )
        assertEquals("readiness is retained", LibraryBaselineReadiness.Confirmed, readiness())
    }

    @Test
    fun `a first library baseline with lifetime playtime never implies history consent or XP`() = runBlocking {
        steamApi.owned = OwnedGamesResponse(OwnedGamesResult(gameCount = 1,
            games = listOf(OwnedGameDto(appid = 440L, name = "Portal", playtimeForever = 600))))
        assertTrue(buildWorker(UUID.randomUUID(), SteamSyncWorker.TRIGGER_MANUAL).doWork() is ListenableWorker.Result.Success)
        assertTrue(db.sessionDao().getAll().isEmpty())
        val game = db.gameDao().getAll().single()
        assertEquals(0, game.backfillMinutes)
        val profile = checkNotNull(db.playerProfileDao().get())
        assertFalse(profile.playtimeBackfilled)
        assertEquals(0L, profile.totalXp)
        assertEquals(LibraryBaselineReadiness.Confirmed, readiness())
    }

    @Test
    fun `overlapping manual and periodic polls each keep their own attributable outcome`() = runBlocking {
        val manualId = UUID.randomUUID()
        val periodicId = UUID.randomUUID()
        seedProfileAccount(accountA)
        db.playerProfileDao().updateSyncStatus(time.now - 30 * 60_000L, null)
        db.gameDao().upsert(Game(440L, "Portal", "", 100, 0, 100,
            lastSyncedAt = time.now - 30 * 60_000L))
        steamApi.owned = OwnedGamesResponse(
            OwnedGamesResult(
                gameCount = 1,
                games = listOf(
                    OwnedGameDto(appid = 440L, name = "Portal", playtimeForever = 120),
                ),
            ),
        )
        val entered = AtomicInteger()
        val bothFetching = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        steamApi.beforeOwned = {
            if (entered.incrementAndGet() == 2) bothFetching.complete(Unit)
            release.await()
        }
        val manualRun = async { buildWorker(manualId, SteamSyncWorker.TRIGGER_MANUAL).doWork() }
        val periodicRun = async { buildWorker(periodicId, SteamSyncWorker.TRIGGER_PERIODIC).doWork() }
        bothFetching.await()
        assertFalse(manualRun.isCompleted)
        assertFalse(periodicRun.isCompleted)
        release.complete(Unit)
        assertTrue(manualRun.await() is ListenableWorker.Result.Success)
        assertTrue(periodicRun.await() is ListenableWorker.Result.Success)

        val manual = pollMapper.resultFor(
            LibraryPollRequest(manualId.toString(), accountA),
            checkNotNull(db.libraryPollEvidenceDao().getFor(manualId.toString(), accountA)).toBoundaryEvidence(),
        )
        val periodic = pollMapper.resultFor(
            LibraryPollRequest(periodicId.toString(), accountA),
            checkNotNull(db.libraryPollEvidenceDao().getFor(periodicId.toString(), accountA)).toBoundaryEvidence(),
        )
        assertEquals(LibraryPollResult.Committed(gameCount = 1, lastSyncAt = time.now), manual)
        assertEquals(LibraryPollResult.Committed(gameCount = 1, lastSyncAt = time.now), periodic)
        assertEquals("two real poll bodies must credit the shared 20-minute increase once", 20,
            db.sessionDao().getAll().sumOf { it.minutes })
        assertEquals(120, db.gameDao().getAll().single().lastPlaytime)
        assertEquals(2, db.libraryPollEvidenceDao().listForAccount(accountA).size)
        assertEquals(LibraryBaselineReadiness.Confirmed, readiness())
    }

    @Test
    fun `a setup-protected latest work survives retention across fresh manual jobs`() = runBlocking {
        // A setup stage is still observing this work; its latest attempt names the exact admitted
        // job id (the scheduler's requestId is only a tag — the actual WM id is the evidence key).
        val protectedWork = UUID.randomUUID()
        setupStore.upsertAttempt(
            StageAttemptRecord(
                stageId = "library_sync",
                generation = 1L,
                admittedWorkId = protectedWork.toString(),
                uniqueWorkName = SteamSyncWorker.ONE_TIME_NAME,
                admissionKind = AdmissionKind.NEW,
                operation = SetupOperationState.Waiting(reason = "constraints not met"),
            ),
        )
        // The protected work committed long ago; retention must still keep its terminal row.
        evidenceRecorder.recordCommitted(protectedWork.toString(), accountA, gameCount = 0, committedAt = BASE_NOW)

        steamApi.owned = OwnedGamesResponse(
            OwnedGamesResult(gameCount = 1, games = listOf(OwnedGameDto(appid = 440L, name = "Portal"))),
        )
        val works = (1..25).map { UUID.randomUUID() }
        works.forEachIndexed { index, workId ->
            time.now = BASE_NOW + 1 + index
            assertTrue(buildWorker(workId, SteamSyncWorker.TRIGGER_MANUAL).doWork() is ListenableWorker.Result.Success)
        }

        val rows = db.libraryPollEvidenceDao().listForAccount(accountA)
        assertTrue(
            "a protected latest setup association is never pruned, even though it is the oldest",
            rows.any { it.workIdentity == protectedWork.toString() },
        )
        assertTrue(rows.any { it.workIdentity == works.last().toString() })
        assertTrue(rows.size <= LibraryPollEvidenceRecorder.RETAINED_OUTCOMES_PER_ACCOUNT + 2)
    }

    @Test
    fun `repeated manual jobs stay bounded by the retention cap`() = runBlocking {
        steamApi.owned = OwnedGamesResponse(
            OwnedGamesResult(gameCount = 1, games = listOf(OwnedGameDto(appid = 440L, name = "Portal"))),
        )
        val works = (1..25).map { UUID.randomUUID() }
        works.forEachIndexed { index, workId ->
            // Each manual job gets a fresh work identity; advance the clock so the newest-N
            // retention is deterministic.
            time.now = BASE_NOW + index
            assertTrue(buildWorker(workId, SteamSyncWorker.TRIGGER_MANUAL).doWork() is ListenableWorker.Result.Success)
        }

        val rows = db.libraryPollEvidenceDao().listForAccount(accountA)
        assertTrue(
            "repeated manual jobs must not accumulate unbounded second-work history",
            rows.size <= LibraryPollEvidenceRecorder.RETAINED_OUTCOMES_PER_ACCOUNT + 1,
        )
        assertTrue("the current attempt's outcome is retained", rows.any { it.workIdentity == works.last().toString() })
        assertTrue("the oldest manual job's terminal row was pruned", rows.none { it.workIdentity == works.first().toString() })
    }

    // --------------------------------------------------------------------------- helpers

    private suspend fun readiness(): LibraryBaselineReadiness =
        pollMapper.readinessFor(
            activeSteamId = accountA,
            pendingResetSteamId = null,
            evidence = db.playerProfileDao().get()?.toBaselineEvidence(),
        )

    private suspend fun seedProfileAccount(steamId: String) {
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().updateSteamIdentity(steamId, 0, null, null, null)
    }

    private fun buildWorker(workId: UUID, trigger: String): SteamSyncWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = SteamSyncWorker(
                appContext = appContext,
                params = workerParameters,
                steamApi = steamApi,
                settings = SettingsDataStore(context),
                credentials = credentials,
                database = db,
                gameDao = db.gameDao(),
                hiddenGameDao = db.hiddenGameDao(),
                sessionDao = db.sessionDao(),
                profileDao = db.playerProfileDao(),
                differ = SessionDiffer(),
                gamificationUpdater = updater,
                achievementRepository = AchievementRepository(
                    steamApi,
                    db.achievementDao(),
                    db.gameAchievementSyncDao(),
                    db.gameDao(),
                    HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time),
                    time,
                ),
                autoSnapshotWriter = snapshots,
                genreEnrichmentScheduler = GenreEnrichmentScheduler(context),
                reviewEnrichmentScheduler = ReviewEnrichmentScheduler(context),
                presenceServiceStarter = PresenceServiceStarter(
                    context,
                    FakeSettingsRepository(),
                    PresenceDecisionRecorder(db.diagnosticsDao(), time),
                    ActivityVisibilityTracker(context),
                ),
                diagnostics = SyncRunRecorder(db.diagnosticsDao(), time),
                time = time,
                syncCoordinator = syncCoordinator,
                accountChangeMarker = marker,
                derivedStateWrites = derivedStateWrites,
                committer = PlaytimeObservationCommitter(
                    gameDao = db.gameDao(),
                    sessionDao = db.sessionDao(),
                    dailyProgressDao = db.dailyProgressDao(),
                    profileDao = db.playerProfileDao(),
                    hiddenGameDao = db.hiddenGameDao(),
                    differ = SessionDiffer(),
                    time = time,
                    sessionActionWriter = SessionActionWriter(
                        db.sessionDao(), db.dailyProgressDao(), db.hiddenGameDao(), time,
                    ),
                    pendingEvidencePruner = CloudPendingEvidencePruner(
                        dao = db.pendingCloudEvidenceDao(),
                        credentials = credentials,
                        settings = FakeSettingsRepository(),
                        readerStateMutex = CloudReaderStateMutex(),
                    ),
                ),
                cloudPresencePlacementReader = CloudPresencePlacementReader { _, _, _ -> null },
                sharedGameConverter = SharedGameConverter(db.gameDao()),
                evidenceRecorder = evidenceRecorder,
                setupStateStore = setupStore,
            )
        }
        return TestListenableWorkerBuilder<SteamSyncWorker>(
            context,
            inputData = workDataOf(SteamSyncWorker.KEY_TRIGGER to trigger),
        )
            .setId(workId)
            .setWorkerFactory(factory)
            .build()
    }

    private class FakeCredentialsProvider(var steamId: String?) : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured? =
            steamId?.let { CredentialsState.Configured(apiKey = "key", steamId = it) }
    }

    /** Only the owned-games + identity endpoints matter; achievement fetches answer empty. */
    private class FakeSteamApi : SteamApi {
        var owned: OwnedGamesResponse = OwnedGamesResponse(OwnedGamesResult(gameCount = 0))
        var failOwned: RuntimeException? = null
        var beforeOwned: suspend () -> Unit = {}

        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
            scope: SyncRunRecorder.RunScope?,
        ): OwnedGamesResponse {
            beforeOwned()
            failOwned?.let { throw it }
            return owned
        }

        override suspend fun getPlayerSummaries(
            key: String,
            steamIds: String,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerSummariesResponse = PlayerSummariesResponse()

        override suspend fun getSteamLevel(
            key: String,
            steamId: String,
            scope: SyncRunRecorder.RunScope?,
        ): SteamLevelResponse = SteamLevelResponse(SteamLevelResult(playerLevel = 42))

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerAchievementsResponse = PlayerAchievementsResponse()

        override suspend fun getGlobalAchievementPercentages(
            gameId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GlobalAchievementPercentagesResponse = GlobalAchievementPercentagesResponse()

        override suspend fun getSchemaForGame(
            key: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GameSchemaResponse = GameSchemaResponse()

        override suspend fun getRecentlyPlayedGames(
            key: String,
            steamId: String,
            count: Int,
            scope: SyncRunRecorder.RunScope?,
        ): RecentlyPlayedGamesResponse = error("not used")

        override suspend fun getWishlist(
            steamId: String,
            scope: SyncRunRecorder.RunScope?,
        ): WishlistResponse = error("not used")

        override suspend fun getStoreItems(
            inputJson: String,
            scope: SyncRunRecorder.RunScope?,
        ): StoreItemsResponse = error("not used")

        override suspend fun resolveVanityUrl(key: String, vanityUrl: String): ResolveVanityResponse =
            error("not used")

        override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse =
            error("not used")
    }

    private class FakeAutoSnapshotWriter : AutoSnapshotWriter {
        var calls = 0
        override suspend fun writeAutoSnapshotIfDue() {
            calls++
        }
    }

    private class MutableTime : TimeProvider {
        var now: Long = BASE_NOW
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-10-02")
    }

    private companion object {
        const val BASE_NOW = 1_700_000_000_000L
    }
}
