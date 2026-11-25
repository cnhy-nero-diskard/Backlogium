package com.example.backlogium.work

import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.PresenceDecisionRecorder
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.RecoveryReadStatusStore
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.OwnedGameDto
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResult
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CloudPresencePlacementReader
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GamificationRecoveryDerivedCompute
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.domain.RecoveryDailyCreditCoordinator
import com.example.backlogium.domain.RecoveryDerivedFinalizer
import com.example.backlogium.domain.RecoveryPlacementReconcileUseCase
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.SharedGameConverter
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.UnknownTimeMaterializer
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SteamSyncLibraryEvidenceWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
        .allowMainThreadQueries().build()
    private val commits = db.libraryPollCommitDao()
    private val marker = AccountChangeMarkerStore(context)
    private var configured = true
    private val credentials = object : CredentialsProvider {
        override suspend fun currentCredentials() =
            CredentialsState.Configured("fixture-key", ACCOUNT).takeIf { configured }
    }
    private val sync = SteamSyncCoordinator()
    private val admissionWakes = mutableListOf<Pair<String, Long>>()
    private val admissionScheduler = object : AchievementAdmissionWorkScheduler {
        override suspend fun enqueue(steamId: String, generation: Long, delayMillis: Long) {
            admissionWakes += steamId to generation
        }
        override suspend fun recoverPending() = Unit
    }
    private val derived = DerivedStateWriteCoordinator()
    private val settings = SettingsDataStore(context)
    private val testSettings = FakeSettingsRepository()
    private var now = Instant.parse("2026-10-01T12:00:00Z")
    private val time = object : TimeProvider {
        override fun nowMillis() = now.toEpochMilli()
        override fun zone() = ZoneOffset.UTC
        override fun today() = now.atOffset(zone()).toLocalDate()
    }
    private val updater = GamificationUpdater(
        db.sessionDao(), db.dailyProgressDao(), db.playerProfileDao(), db.hltbDataDao(),
        db.achievementDao(), db.gameDao(), db.hiddenGameDao(),
    )
    private val dailyCredit = RecoveryDailyCreditCoordinator(db.dailyProgressDao())
    private var ownedGames: suspend () -> OwnedGamesResponse = { OwnedGamesResponse(OwnedGamesResult(0)) }
    private val unusedApi = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) {
        _, method, _ -> throw IOException("No fixture for ${method.name}")
    } as SteamApi
    private val api = object : SteamApi by unusedApi {
        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
            scope: SyncRunRecorder.RunScope?,
        ) = ownedGames()
    }

    @After
    fun close() = db.close()

    @Test
    fun missingCredentialsAndRefusedAdmissionEachReportTheirOwnOperation() = runTest {
        ownedGames = { error("Unadmitted worker reached Steam") }
        configured = false
        val unconfigured = worker()
        val missing = output(unconfigured)
        assertEquals(SteamSyncWorker.POLL_NOT_PERFORMED, missing.getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertEquals(unconfigured.id.toString(), missing.getString(SteamSyncWorker.KEY_POLL_OPERATION))
        assertTrue(missing.getString(SteamSyncWorker.KEY_SETUP_FAILURE)!!.contains("Connect"))

        configured = true
        marker.markPending("replacement")
        try {
            val refused = worker()
            val blocked = output(refused)
            assertEquals(SteamSyncWorker.POLL_NOT_PERFORMED, blocked.getString(SteamSyncWorker.KEY_POLL_RESULT))
            assertEquals(refused.id.toString(), blocked.getString(SteamSyncWorker.KEY_POLL_OPERATION))
            assertTrue(blocked.getString(SteamSyncWorker.KEY_SETUP_FAILURE)!!.contains("account change"))
            assertFalse(commits.hasBaseline(ACCOUNT))
        } finally {
            marker.clear()
        }
    }

    @Test
    fun confirmedEmptyCommitsAndUnreadableRefreshHasItsOwnOutcome() = runTest {
        ownedGames = { OwnedGamesResponse() }
        val unreadable = worker()
        assertEquals(SteamSyncWorker.POLL_NOT_PERFORMED, output(unreadable).getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertFalse(commits.hasBaseline(ACCOUNT))

        ownedGames = { OwnedGamesResponse(OwnedGamesResult(0)) }
        val empty = worker()
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(empty).getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertEquals(0, commits.get(empty.id.toString(), ACCOUNT)?.gameCount)
        assertTrue(db.gameDao().getAll().isEmpty())

        ownedGames = { throw IOException("fixture connection loss") }
        val failed = worker()
        assertEquals(Result.retry(), failed.doWork())
        assertNull(commits.get(failed.id.toString(), ACCOUNT))
        assertTrue(commits.hasBaseline(ACCOUNT))
    }

    @Test
    fun newOwnedGamesAreQueuedInsideTheConfirmedPollButBaselineGamesAreNot() = runTest {
        ownedGames = { snapshot(100) }
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))
        val generation = marker.generation()
        assertTrue(db.achievementAdmissionDao().eligible(ACCOUNT, generation, Long.MAX_VALUE, 20).isEmpty())

        now = now.plusSeconds(30 * 60)
        ownedGames = {
            OwnedGamesResponse(OwnedGamesResult(2, listOf(
                OwnedGameDto(10, "Fixture", playtimeForever = 100),
                OwnedGameDto(11, "New zero-play game", playtimeForever = 0),
            )))
        }
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))

        val pending = db.achievementAdmissionDao().eligible(ACCOUNT, generation, Long.MAX_VALUE, 20)
        assertEquals(listOf(11L), pending.map { it.appId })
        assertEquals(listOf(ACCOUNT to generation), admissionWakes)
    }

    @Test
    fun sameIncreaseObservedByOverlappingManualAndPeriodicPollsIsOnlyCreditedOnce() = runTest {
        ownedGames = { snapshot(100) }
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))
        now = now.plusSeconds(30 * 60)
        val arrived = AtomicInteger()
        val bothFetching = CompletableDeferred<Unit>()
        ownedGames = {
            if (arrived.incrementAndGet() == 2) bothFetching.complete(Unit)
            bothFetching.await()
            snapshot(120)
        }
        val manual = worker()
        val periodic = worker(SteamSyncWorker.TRIGGER_PERIODIC)
        val manualAttempt = async { output(manual) }
        val periodicAttempt = async { output(periodic) }
        val manualOperation = manualAttempt.await().getString(SteamSyncWorker.KEY_POLL_OPERATION)!!
        val periodicOperation = periodicAttempt.await().getString(SteamSyncWorker.KEY_POLL_OPERATION)!!
        assertNotEquals(manualOperation, periodicOperation)
        assertEquals(manual.id.toString(), commits.get(manualOperation, ACCOUNT)?.workId)
        assertEquals(periodic.id.toString(), commits.get(periodicOperation, ACCOUNT)?.workId)
        assertEquals(20, db.sessionDao().creditedMinutesByGame().single().minutes)
    }

    @Test
    fun receiptRollbackAndRetryAfterRawCommitUseTheSameManualOperation() = runTest {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fixture_rollback BEFORE INSERT ON library_poll_commits " +
            "BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END")
        ownedGames = { snapshot(100) }
        val initial = worker()
        assertEquals(Result.retry(), initial.doWork())
        assertTrue(db.gameDao().getAll().isEmpty())
        assertFalse(commits.hasBaseline(ACCOUNT))
        sql.execSQL("DROP TRIGGER fixture_rollback")

        sql.execSQL("CREATE TRIGGER fixture_interruption BEFORE UPDATE ON library_poll_commits " +
            "BEGIN SELECT RAISE(ABORT, 'fixture interruption after raw commit'); END")
        assertEquals(Result.retry(), worker(id = initial.id).doWork())
        assertEquals(100, db.gameDao().getAll().single().playtimeForever)
        assertEquals(false, commits.get(initial.id.toString(), ACCOUNT)?.finalized)
        sql.execSQL("DROP TRIGGER fixture_interruption")

        ownedGames = { error("Receipt recovery must not refetch the library") }
        val replay = output(worker(id = initial.id))
        assertEquals(initial.id.toString(), replay.getString(SteamSyncWorker.KEY_POLL_OPERATION))
        assertEquals(SteamSyncWorker.POLL_COMMITTED, replay.getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertEquals(true, commits.get(initial.id.toString(), ACCOUNT)?.finalized)
        assertTrue(db.sessionDao().creditedMinutesByGame().isEmpty())
    }

    private suspend fun output(worker: SteamSyncWorker) = (worker.doWork() as Result.Success).outputData

    private fun snapshot(minutes: Int) =
        OwnedGamesResponse(OwnedGamesResult(1, listOf(OwnedGameDto(10, "Fixture", playtimeForever = minutes))))

    private fun worker(trigger: String = SteamSyncWorker.TRIGGER_MANUAL, id: UUID = UUID.randomUUID()): SteamSyncWorker {
        val games = db.gameDao()
        val sessions = db.sessionDao()
        val profile = db.playerProfileDao()
        val hidden = db.hiddenGameDao()
        val daily = db.dailyProgressDao()
        val recovery = db.playtimeRecoveryDao()
        val differ = SessionDiffer()
        return TestListenableWorkerBuilder<SteamSyncWorker>(
            context, inputData = workDataOf(SteamSyncWorker.KEY_TRIGGER to trigger),
        ).setId(id).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SteamSyncWorker(
                    appContext, workerParameters, api, settings, credentials, db, games, hidden, sessions, profile,
                    differ, updater, AchievementRepository(
                        api, db.achievementDao(), db.gameAchievementSyncDao(), games,
                        HiddenGamesRepository(hidden, games, db.gameGenreCacheDao(), time), time,
                    ),
                    { error("Backup snapshot is outside the poll fixture") },
                    GenreEnrichmentScheduler(context), ReviewEnrichmentScheduler(context),
                    PresenceServiceStarter(context, testSettings, PresenceDecisionRecorder(db.diagnosticsDao(), time),
                        ActivityVisibilityTracker(context)),
                    SyncRunRecorder(db.diagnosticsDao(), time), time, sync, marker, derived,
                    PlaytimeObservationCommitter(
                        games, sessions, daily, profile, hidden, differ, time,
                        SessionActionWriter(sessions, daily, hidden, time),
                        PlaytimeRecoveryCommitAdapter(recovery, sessions),
                    ),
                    CloudPresencePlacementReader { _, _, _ -> null },
                    SharedGameConverter(games, recovery, time),
                    UnknownTimeMaterializer(db, recovery, dailyCredit),
                    RecoveryDerivedFinalizer(recovery, derived, GamificationRecoveryDerivedCompute(updater)),
                    RecoveryPlacementReconcileUseCase(
                        recovery, sessions, dailyCredit, testSettings, updater, time, sync, derived,
                        RoomDatabaseTransactionScope(db),
                    ),
                    RecoveryReadStatusStore(context),
                    admissionScheduler,
                )
        }).build()
    }

    private companion object {
        const val ACCOUNT = "fixture-account"
    }
}
