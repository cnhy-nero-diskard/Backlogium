package com.example.backlogium.work.setup

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.history.DataStoreHistoryImportRequestStore
import com.example.backlogium.data.history.DefaultHistoryImportAccountGateway
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.LibraryPollEvidenceDao
import com.example.backlogium.data.local.entity.LibraryPollEvidenceRecord
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.LibraryPollRefusal
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.HistoryImportCoordinator
import com.example.backlogium.domain.PlaytimeBackfillUseCase
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.LibraryPollEvidenceRecorder
import com.example.backlogium.work.SteamSyncCoordinator
import com.example.backlogium.work.SteamSyncWorker
import com.example.backlogium.work.SyncScheduler
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * End-to-end consumer coverage for the library stage's attributable terminal result (registry +
 * runners + tests scope): the registered `library_sync` stage admits over the real scheduler and
 * real WorkManager, while the **real Room producer record** (`LibraryPollEvidenceRecorder`) for the
 * exact admitted work/account drives the terminal classification via
 * `ProfileRepository.libraryPollResult` — WorkManager scheduler success alone is never a
 * library-sync success.
 *
 * Not a fake assertion: the Room database, DAO, evidence recorder, `ProfileRepository`, and
 * `SetupStageRegistry` are all real; only the Steam worker *body* and the account are doubles. The
 * WorkManager harness mirrors the characterization fixture (real worker executor, pinned
 * [TestDriver], orderly real-time flushing) so terminal states genuinely arrive.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStageAttributableResultTest {

    private lateinit var context: Context
    private lateinit var database: BacklogiumDatabase
    private lateinit var evidenceDao: LibraryPollEvidenceDao
    private lateinit var recorder: LibraryPollEvidenceRecorder
    private lateinit var credentials: FakeCredentialsProvider
    private lateinit var profile: ProfileRepository
    private lateinit var registry: SetupStageRegistry
    private lateinit var driver: TestDriver
    private lateinit var schedulerScope: CoroutineScope
    private lateinit var workerExecutor: ExecutorService

    private val accountA = "76561198000000000"
    private val confirmedAt = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Install this fixture's WorkManager BEFORE any scheduler/registry captures getInstance.
        // The preceding characterization fixture intentionally closes its WorkManager database.
        workerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "attributable-library-worker").apply { isDaemon = true }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(workerExecutor)
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = when (workerClassName) {
                        SteamSyncWorker::class.java.name -> AttributableSyncDouble(appContext, workerParameters)
                        else -> error("unexpected worker for attributable-library test: $workerClassName")
                    }
                })
                .build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        evidenceDao = database.libraryPollEvidenceDao()
        recorder = LibraryPollEvidenceRecorder(evidenceDao)
        credentials = FakeCredentialsProvider(accountA)
        val marker = AccountChangeMarkerStore(context)
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
            transaction = RoomDatabaseTransactionScope(database),
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
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        profile = ProfileRepository(
            profileDao = profileDao,
            dailyProgressDao = dailyProgressDao,
            syncScheduler = SyncScheduler(context, schedulerScope),
            historyImport = historyImport,
            credentials = credentials,
            accountChangeMarker = marker,
            evidenceDao = evidenceDao,
        )
        val scheduler = SyncScheduler(context, schedulerScope)
        registry = SetupStageRegistry(context, scheduler, profile, credentials)

    }

    @After
    fun tearDown() {
        runCatching { closeWorkManager() }
        database.close()
        schedulerScope.cancel()
        workerExecutor.shutdownNow()
    }

    @Test
    fun committedEmptyBaselineForExactWorkAndAccountIsAnHonestEmptySuccess() = runTest {
        val work = admitWorkAsync()

        // The Room producer record for this exact admitted work is committed with a confirmed zero.
        recorder.recordCommitted(work.workId.toString(), accountA, gameCount = 0, committedAt = confirmedAt)
        driver.setAllConstraintsMet(work.workId)
        flushUntil { settledResultOf(work) != null }

        val terminal = checkNotNull(settledResultOf(work))
        assertTrue("confirmed empty is a successful empty baseline", terminal is SetupOperationState.Succeeded)
        val detail = (terminal as SetupOperationState.Succeeded).detail
        assertNotNull(detail)
        assertTrue(detail.orEmpty().contains("empty"))
    }

    @Test
    fun committedEvidenceWithGamesIsASuccessWithoutInventedDetail() = runTest {
        val work = admitWorkAsync()

        recorder.recordCommitted(work.workId.toString(), accountA, gameCount = 137, committedAt = confirmedAt)
        driver.setAllConstraintsMet(work.workId)
        flushUntil { settledResultOf(work) != null }

        val terminal = checkNotNull(settledResultOf(work))
        assertTrue(terminal is SetupOperationState.Succeeded)
        assertTrue((terminal as SetupOperationState.Succeeded).detail == null)
    }

    @Test
    fun schedulerSuccessWithoutRoomEvidenceIsRecoveryRequiredNotSuccess() = runTest {
        val work = admitWorkAsync()

        // No producer record for this exact work: WorkManager finishing must never be a success.
        driver.setAllConstraintsMet(work.workId)
        flushUntil { settledResultOf(work) != null }

        assertTrue(settledResultOf(work) is SetupOperationState.RecoveryRequired)
    }

    @Test
    fun privateOrUnconfirmedResponseIsAnAttributableFailureNotALibrarySuccess() = runTest {
        val work = admitWorkAsync()

        evidenceDao.upsert(
            LibraryPollEvidenceRecord(
                workIdentity = work.workId.toString(),
                accountSteamId = accountA,
                outcome = LibraryPollOutcomeKind.NOT_PERFORMED.name,
                gameCount = -1,
                lastSyncAt = 0L,
                refusal = LibraryPollRefusal.UNCONFIRMED_EMPTY.name,
                reason = null,
                recordedAt = confirmedAt,
            ),
        )
        driver.setAllConstraintsMet(work.workId)
        flushUntil { settledResultOf(work) != null }

        val terminal = checkNotNull(settledResultOf(work))
        assertTrue(terminal is SetupOperationState.Failed)
        assertTrue((terminal as SetupOperationState.Failed).reason.orEmpty().contains("private"))
    }

    private suspend fun TestScope.admitWorkAsync(): StageAdmission.Work {
        val deferred = async { registry.libraryStage().run.admit(UUID.randomUUID().toString()) }
        flushUntil { deferred.isCompleted }
        return deferred.await() as StageAdmission.Work
    }

    private suspend fun TestScope.settledResultOf(work: StageAdmission.Work): SetupOperationState? =
        // Non-blocking probe: the runner's observe flow emits the CURRENT state first, so `first()`
        // returns immediately; flushUntil keeps re-probing until the work actually reaches a
        // settled state. RecoveryRequired is deliberately not `isTerminal` in the model, but it IS
        // the settled result an Unknown attributable library outcome must surface — never weakened
        // into a manufactured success.
        registry.libraryStage().run.observe(work.workId.toString())
            .first()
            .takeIf { it.isTerminal || it is SetupOperationState.RecoveryRequired }

    private fun SetupStageRegistry.libraryStage(): SetupStage =
        stages.single { it.id == SetupStageRegistry.STAGE_LIBRARY_SYNC }

    private fun TestScope.flush() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
    }

    private suspend fun TestScope.flushUntil(attempts: Int = 80, condition: suspend () -> Boolean) {
        repeat(attempts) {
            flush()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun closeWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
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

private class AttributableSyncDouble(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
