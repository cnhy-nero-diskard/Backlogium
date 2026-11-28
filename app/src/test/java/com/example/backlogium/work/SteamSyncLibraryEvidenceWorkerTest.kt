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
import com.example.backlogium.data.local.entity.Game
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
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
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
    private val operationReports = OperationReportStore(context)
    private var configured = true
    private val credentials = object : CredentialsProvider {
        override suspend fun currentCredentials() =
            CredentialsState.Configured("fixture-key", ACCOUNT).takeIf { configured }
    }
    private val sync = SteamSyncCoordinator()
    private val admissionWakes = mutableListOf<Pair<String, Long>>()
    private val scheduledAdmissionIdentities = mutableSetOf<Pair<String, Long>>()
    private var failAdmissionWake = false
    private val admissionScheduler = object : AchievementAdmissionWorkScheduler {
        override suspend fun enqueue(steamId: String, generation: Long, delayMillis: Long) {
            if (failAdmissionWake) throw IOException("fixture scheduler interruption")
            val identity = steamId to generation
            if (scheduledAdmissionIdentities.add(identity)) admissionWakes += identity
        }
        override suspend fun recoverPending() {
            db.achievementAdmissionDao().pendingIdentities().forEach { identity ->
                enqueue(identity.steamId, identity.accountGeneration)
            }
        }
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
    private var failPlayerAchievements = false
    private var cancelledPlayerAchievementRequest: Int? = null
    private var playerAchievementRequests = 0
    private var playerAchievements = listOf(
        com.example.backlogium.data.remote.dto.PlayerAchievementDto("ACH_1", achieved = 1, unlocktime = 5L),
    )
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

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): com.example.backlogium.data.remote.dto.PlayerAchievementsResponse {
            if (playerAchievementRequests++ == cancelledPlayerAchievementRequest) {
                throw CancellationException("fixture constraint stop")
            }
            if (failPlayerAchievements) throw IOException("fixture achievement failure")
            return com.example.backlogium.data.remote.dto.PlayerAchievementsResponse(
                com.example.backlogium.data.remote.dto.PlayerAchievementsResult(
                    success = true, achievements = playerAchievements,
                ),
            )
        }

        override suspend fun getSchemaForGame(
            key: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ) = com.example.backlogium.data.remote.dto.GameSchemaResponse(
            com.example.backlogium.data.remote.dto.GameSchemaResult(
                com.example.backlogium.data.remote.dto.AvailableGameStatsDto(
                    achievements = listOf(com.example.backlogium.data.remote.dto.AchievementSchemaDto(name = "ACH_1")),
                ),
            ),
        )

        override suspend fun getGlobalAchievementPercentages(
            gameId: Long,
            scope: SyncRunRecorder.RunScope?,
        ) = com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse()
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
        operationReports.clearForTest()
        ownedGames = { OwnedGamesResponse() }
        val unreadable = worker()
        assertEquals(SteamSyncWorker.POLL_NOT_PERFORMED, output(unreadable).getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertFalse(commits.hasBaseline(ACCOUNT))

        ownedGames = { OwnedGamesResponse(OwnedGamesResult(0)) }
        val empty = worker()
        val emptyOutput = output(empty)
        assertEquals(SteamSyncWorker.POLL_COMMITTED, emptyOutput.getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertEquals(com.example.backlogium.domain.OperationCounts(0, 0), emptyOutput.operationCounts())
        assertEquals(0, commits.get(empty.id.toString(), ACCOUNT)?.gameCount)
        assertEquals(0, commits.get(empty.id.toString(), ACCOUNT)?.achievementTotal)
        assertEquals(0, emptyOutput.getInt(OP_UPDATED, -1))
        assertTrue(db.gameDao().getAll().isEmpty())

        ownedGames = { throw IOException("fixture connection loss") }
        val failed = worker()
        assertEquals(Result.retry(), failed.doWork())
        assertNull(commits.get(failed.id.toString(), ACCOUNT))
        assertTrue(commits.hasBaseline(ACCOUNT))
    }

    @Test
    fun committedInlineAchievementEvidenceIsMeasuredAndRecoveredWithoutInventedCounts() = runTest {
        operationReports.clearForTest()
        ownedGames = {
            OwnedGamesResponse(OwnedGamesResult(1, listOf(
                OwnedGameDto(10, "Fixture", playtimeForever = 100, playtime2Weeks = 10),
            )))
        }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fixture_finalization_interrupt BEFORE UPDATE ON library_poll_commits " +
            "BEGIN SELECT RAISE(ABORT, 'fixture finalization interruption'); END")

        val first = worker()
        assertEquals(Result.retry(), first.doWork())
        val receipt = commits.get(first.id.toString(), ACCOUNT)!!
        assertEquals(1, receipt.achievementProcessed)
        assertEquals(1, receipt.achievementTotal)
        assertEquals(0, receipt.achievementSkipped)
        assertEquals(0, receipt.achievementFailed)
        assertEquals(1, receipt.achievementUpdated)
        sql.execSQL("DROP TRIGGER fixture_finalization_interrupt")

        val recovered = output(worker(id = first.id))
        assertEquals(SteamSyncWorker.POLL_COMMITTED, recovered.getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertEquals(com.example.backlogium.domain.OperationCounts(1, 1), recovered.operationCounts())
        assertEquals(1, recovered.getInt(OP_UPDATED, -1))

        val unchanged = output(worker())
        assertEquals(com.example.backlogium.domain.OperationCounts(1, 1), unchanged.operationCounts())
        assertEquals("unchanged fetched content is not reported as changed", 0, unchanged.getInt(OP_UPDATED, -1))
    }

    @Test
    fun partialInlineFailureIsRetainedInThePollReceiptAndNotReplacedByLibrarySize() = runTest {
        operationReports.clearForTest()
        failPlayerAchievements = true
        ownedGames = {
            OwnedGamesResponse(OwnedGamesResult(1, listOf(
                OwnedGameDto(10, "Fixture", playtimeForever = 100, playtime2Weeks = 10),
            )))
        }

        val first = worker()
        val output = output(first)
        val receipt = commits.get(first.id.toString(), ACCOUNT)!!

        assertEquals(com.example.backlogium.domain.OperationCounts(1, 1, failed = 1), output.operationCounts())
        assertEquals(0, output.getInt(OP_UPDATED, -1))
        assertEquals(1, receipt.achievementFailed)
        assertEquals(0, receipt.achievementUpdated)
        val info = androidx.work.WorkInfo(
            first.id, androidx.work.WorkInfo.State.SUCCEEDED,
            setOf("setup-account:${marker.generation()}"), output,
        )
        assertEquals(com.example.backlogium.domain.OperationStatus.PARTIAL,
            operationProgress(info, marker.generation())?.status)
    }

    @Test
    fun constraintInterruptedSteamAndReconciliationWorkersPersistTheirMeasuredSnapshots() = runTest {
        operationReports.clearForTest()
        playerAchievementRequests = 0
        cancelledPlayerAchievementRequest = 5
        ownedGames = {
            OwnedGamesResponse(OwnedGamesResult(10, (10L..19L).map { appId ->
                OwnedGameDto(appId, "Fixture $appId", playtimeForever = 100, playtime2Weeks = 10)
            }))
        }
        val steam = worker()
        try {
            steam.doWork()
            fail("the configured achievement request must interrupt the poll")
        } catch (_: CancellationException) {
            // WorkManager also uses cancellation when constraints stop a running worker.
        }

        val steamReport = operationReports.observe(OperationChannel.STEAM).first().single()
        assertEquals(OperationSource.MANUAL, steamReport.source)
        assertEquals(steam.id.toString(), steamReport.workId)
        assertEquals(OperationStatus.RUNNING, steamReport.progress.status)
        assertEquals(OperationPhase.ACHIEVEMENTS, steamReport.progress.phase)
        assertEquals(com.example.backlogium.domain.OperationCounts(5, 10), steamReport.progress.counts)

        operationReports.clearForTest()
        db.gameDao().upsertAll((20L..29L).map { appId ->
            Game(appId = appId, name = "Cold $appId", iconUrl = "", playtimeForever = 100,
                playtime2Weeks = 0, lastPlaytime = 0)
        })
        playerAchievementRequests = 0
        val reconciliation = reconciliationWorker()
        try {
            reconciliation.doWork()
            fail("the configured achievement request must interrupt reconciliation")
        } catch (_: CancellationException) {
            // Do not turn a constraint interruption into a terminal cancellation report.
        }

        val reconciliationReport = operationReports.observe(OperationChannel.ACHIEVEMENTS).first().single()
        assertEquals(OperationSource.PERIODIC, reconciliationReport.source)
        assertEquals(reconciliation.id.toString(), reconciliationReport.workId)
        assertEquals(OperationStatus.RUNNING, reconciliationReport.progress.status)
        assertEquals(OperationPhase.ACHIEVEMENTS, reconciliationReport.progress.phase)
        assertEquals(com.example.backlogium.domain.OperationCounts(5, 10), reconciliationReport.progress.counts)
        assertEquals(5, reconciliationReport.progress.updated)
    }

    @Test
    fun newOwnedGamesAreQueuedInsideTheConfirmedPollButBaselineGamesAreNot() = runTest {
        ownedGames = { snapshot(100) }
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))
        val generation = marker.generation()
        assertTrue(db.achievementAdmissionDao().eligible(ACCOUNT, generation, Long.MAX_VALUE, 20).isEmpty())
        assertTrue(db.progressAlertDao().pending().isEmpty())

        now = now.plusSeconds(30 * 60)
        db.gameDao().upsert(Game(
            appId = 12L, name = "Borrowed game", iconUrl = "", playtimeForever = 0,
            playtime2Weeks = 0, lastPlaytime = 0, source = GameSource.FAMILY_SHARED,
        ))
        ownedGames = {
            OwnedGamesResponse(OwnedGamesResult(3, listOf(
                OwnedGameDto(10, "Fixture", playtimeForever = 100),
                OwnedGameDto(11, "New zero-play game", playtimeForever = 0),
                OwnedGameDto(12, "Borrowed game", playtimeForever = 50),
            )))
        }
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))

        val pending = db.achievementAdmissionDao().eligible(ACCOUNT, generation, Long.MAX_VALUE, 20)
        assertEquals(listOf(11L), pending.map { it.appId })
        assertEquals(GameSource.STEAM_OWNED, db.gameDao().getById(12L)?.source)
        assertEquals(listOf(ACCOUNT to generation), admissionWakes)

        // Seeing the same library snapshot again does not create another admission or wake.
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertEquals(listOf(11L), db.achievementAdmissionDao().eligible(ACCOUNT, generation, Long.MAX_VALUE, 20).map { it.appId })
        assertEquals(listOf(ACCOUNT to generation), admissionWakes)
    }

    @Test
    fun restoredGamesAreNotReclassifiedAsNewAdmissions() = runTest {
        db.gameDao().upsert(Game(
            appId = 10L, name = "Restored game", iconUrl = "", playtimeForever = 100,
            playtime2Weeks = 0, lastPlaytime = 0,
        ))
        ownedGames = { snapshot(100) }

        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertTrue(db.achievementAdmissionDao().pendingIdentities().isEmpty())
        assertTrue(admissionWakes.isEmpty())
    }

    @Test
    fun committedAdmissionIsRecoveredWhenThePostCommitWakeFails() = runTest {
        ownedGames = { snapshot(100) }
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))
        now = now.plusSeconds(30 * 60)
        ownedGames = {
            OwnedGamesResponse(OwnedGamesResult(2, listOf(
                OwnedGameDto(10, "Fixture", playtimeForever = 100),
                OwnedGameDto(11, "New zero-play game", playtimeForever = 0),
            )))
        }
        failAdmissionWake = true

        val failedHandoff = worker()
        assertEquals(Result.retry(), failedHandoff.doWork())
        val generation = marker.generation()
        assertEquals(listOf(11L), db.achievementAdmissionDao().eligible(ACCOUNT, generation, Long.MAX_VALUE, 20).map { it.appId })
        assertTrue(admissionWakes.isEmpty())

        failAdmissionWake = false
        ownedGames = { error("The committed retry must not fetch the library again") }
        val replay = output(worker(id = failedHandoff.id))
        assertEquals(SteamSyncWorker.POLL_COMMITTED, replay.getString(SteamSyncWorker.KEY_POLL_RESULT))
        assertEquals(listOf(ACCOUNT to generation), admissionWakes)
    }

    @Test
    fun unchangedOwnedSyncRecoversPendingSharedAdmission() = runTest {
        ownedGames = { snapshot(100) }
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))
        val generation = marker.generation()
        val sharedAppId = 11L
        db.gameDao().upsert(Game(
            appId = sharedAppId, name = "Shared", iconUrl = "", playtimeForever = 0,
            playtime2Weeks = 0, lastPlaytime = 0, source = GameSource.FAMILY_SHARED,
        ))
        db.achievementAdmissionDao().insert(
            com.example.backlogium.data.local.entity.AchievementAdmissionWork(
                ACCOUNT, generation, sharedAppId, now.toEpochMilli(),
            ),
        )

        assertTrue(admissionWakes.isEmpty())
        assertEquals(SteamSyncWorker.POLL_COMMITTED, output(worker()).getString(SteamSyncWorker.KEY_POLL_RESULT))

        assertEquals(listOf(ACCOUNT to generation), admissionWakes)
        assertEquals(listOf(sharedAppId), db.achievementAdmissionDao()
            .eligible(ACCOUNT, generation, Long.MAX_VALUE, 20).map { it.appId })
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

    @Test fun ownedAlertSurvivesProducerInterruptionBeforeAnyNotificationEnqueue() = runTest {
        val platform = com.example.backlogium.data.notifications.ProgressAlertPlatform(context)
        platform.registerChannels()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        settings.setProgressAlert(com.example.backlogium.data.notifications.ProgressCategory.OWNED, true)
        ownedGames = { snapshot(0) }
        output(worker())
        assertTrue(db.progressAlertDao().pending().isEmpty())
        now = now.plusSeconds(60)
        ownedGames = { OwnedGamesResponse(OwnedGamesResult(2, listOf(
            OwnedGameDto(10, "Fixture", playtimeForever = 0), OwnedGameDto(11, "New", playtimeForever = 0),
        ))) }
        failAdmissionWake = true
        assertEquals(Result.retry(), worker().doWork())
        val alert = db.progressAlertDao().pending().single()
        assertEquals(11L, alert.appId)
        assertEquals(11L, db.achievementAdmissionDao().get(alert.account, alert.generation, alert.sourceId.toLong())?.appId)
        val recovered = com.example.backlogium.data.notifications.ProgressAlerts(db, settings, platform, marker, credentials, sync)
        recovered.dispatch()
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        assertEquals(1, notifications.activeNotifications.size)
        notifications.cancelAll()
        failAdmissionWake = false
        output(worker())
        recovered.dispatch()
        assertTrue(notifications.activeNotifications.isEmpty())
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
                    differ, updater, achievementRepository(),
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
                    db.achievementAdmissionDao(),
                    com.example.backlogium.data.notifications.ProgressAlerts(db, settings,
                        com.example.backlogium.data.notifications.ProgressAlertPlatform(context), marker, credentials, sync),
                )
        }).build()
    }

    private fun achievementRepository(): AchievementRepository {
        val games = db.gameDao()
        val hidden = db.hiddenGameDao()
        return AchievementRepository(
            api, db.achievementDao(), db.gameAchievementSyncDao(), games,
            HiddenGamesRepository(hidden, games, db.gameGenreCacheDao(), time), time,
        )
    }

    private fun reconciliationWorker(id: UUID = UUID.randomUUID()): ReconciliationWorker =
        TestListenableWorkerBuilder<ReconciliationWorker>(context).setId(id).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                ReconciliationWorker(
                    appContext, workerParameters, credentials, db, achievementRepository(),
                    SyncRunRecorder(db.diagnosticsDao(), time), sync, marker, operationReports,
                )
        }).build()

    private companion object {
        const val ACCOUNT = "fixture-account"
    }
}
