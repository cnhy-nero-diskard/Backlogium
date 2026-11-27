package com.example.backlogium.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationStatus
import com.example.backlogium.ui.setup.setupStagesUi
import com.example.backlogium.work.setup.SetupRunState
import com.example.backlogium.work.setup.SetupStageRegistry
import com.example.backlogium.work.setup.fakeStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * Covers the enqueue path itself — work names, [androidx.work.ExistingWorkPolicy]/
 * [androidx.work.ExistingPeriodicWorkPolicy] behaviour, and constraints — using a real
 * [WorkManager] backed by [SynchronousExecutor] rather than asserting on [SyncScheduler]'s calls
 * into a mock. No worker is ever allowed to actually run: none of the constraints below are
 * satisfied by [WorkManagerTestInitHelper]'s default trackers, so every request this file
 * enqueues stays `ENQUEUED`, which is exactly the state these tests need to inspect.
 *
 * The centerpiece is the pair of tests confirming the periodic and one-time reconciliation
 * passes do not drop each other — a regression test for a real defect found in review: both used
 * to share one unique work name, so `KEEP` silently dropped the manual/forced pass (the Settings
 * "full refresh" action, and the post-restore kick) whenever the periodic work — which sits
 * `ENQUEUED` almost permanently, since its charging+unmetered constraints are rarely met — was
 * already scheduled. That is most of the time.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SyncScheduler
    private lateinit var schedulerScope: CoroutineScope
    private lateinit var operationReports: OperationReportStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        operationReports = OperationReportStore(context)
        runBlocking { operationReports.clearForTest() }
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scheduler = SyncScheduler(context, schedulerScope, operationReports = operationReports)
    }

    @After
    fun tearDown() {
        schedulerScope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `ensurePeriodicReconciliation and reconcileNow are not silently dropped by each other`() = runTest {
        scheduler.ensurePeriodicReconciliation()
        scheduler.reconcileNow()

        val periodic = workInfosFor(ReconciliationWorker.PERIODIC_NAME)
        val oneTime = workInfosFor(ReconciliationWorker.ONE_TIME_NAME)

        assertEquals("the periodic pass must still be enqueued", 1, periodic.size)
        assertEquals(WorkInfo.State.ENQUEUED, periodic.single().state)
        assertEquals(
            "the manual pass must be enqueued too, not dropped by the periodic work's KEEP policy",
            1,
            oneTime.size,
        )
        assertEquals(WorkInfo.State.ENQUEUED, oneTime.single().state)
        assertNotEquals(
            "the two passes must be genuinely separate work, not the same request seen twice",
            periodic.single().id,
            oneTime.single().id,
        )
    }

    @Test
    fun `reconcileNow is order-independent — calling it before the periodic pass still enqueues both`() = runTest {
        scheduler.reconcileNow()
        scheduler.ensurePeriodicReconciliation()

        assertEquals(1, workInfosFor(ReconciliationWorker.ONE_TIME_NAME).size)
        assertEquals(1, workInfosFor(ReconciliationWorker.PERIODIC_NAME).size)
    }

    @Test
    fun `reconciliationInProgress does not read true from the periodic pass sitting enqueued`() = runTest {
        scheduler.ensurePeriodicReconciliation()

        // The periodic pass is ENQUEUED (not RUNNING) — SyncScheduler's own contract is that this
        // alone must not read as "in progress", since it sits ENQUEUED for days by design.
        assertFalse(scheduler.reconciliationInProgress.first())
    }

    @Test
    fun `reconciliationInProgress reads true once the manual pass is enqueued`() = runTest {
        scheduler.ensurePeriodicReconciliation()
        scheduler.reconcileNow()

        assertTrue(scheduler.reconciliationInProgress.first())
    }

    @Test
    fun `reconcileNow without force requires charging and unmetered network`() = runTest {
        scheduler.reconcileNow(force = false)

        val constraints = workInfosFor(ReconciliationWorker.ONE_TIME_NAME).single().constraints
        assertTrue(constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
    }

    @Test
    fun `reconcileNow with force bypasses the charging and unmetered requirement`() = runTest {
        scheduler.reconcileNow(force = true)

        val constraints = workInfosFor(ReconciliationWorker.ONE_TIME_NAME).single().constraints
        assertFalse(constraints.requiresCharging())
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    @Test
    fun `reconciliation request captures the same account generation in payload and tag`() = runTest {
        scheduler.reconcileNow(force = true)

        val info = workInfosFor(ReconciliationWorker.ONE_TIME_NAME).single()
        val payloadGeneration = inputFor(ReconciliationWorker.ONE_TIME_NAME).getLong(OP_ACCOUNT, -1L)
        val tagGeneration = info.tags.first { it.startsWith("setup-account:") }.substringAfter(":").toLong()

        assertEquals(tagGeneration, payloadGeneration)
    }

    /**
     * A restore enqueues the unforced, constrained pass (`BackupRepository.importBackup()`); the
     * player can then tap "full refresh" (forced) while it is still sitting `ENQUEUED` waiting for
     * charging + unmetered wifi. Both calls share [ReconciliationWorker.ONE_TIME_NAME] — if both
     * used `KEEP`, the already-queued unforced request would silently block the forced one from
     * ever superseding it, defeating the entire point of forcing.
     */
    @Test
    fun `a forced reconcileNow replaces an already-queued unforced one`() = runTest {
        scheduler.reconcileNow(force = false)
        val unforcedId = workInfosFor(ReconciliationWorker.ONE_TIME_NAME).single().id

        scheduler.reconcileNow(force = true)

        val infos = workInfosFor(ReconciliationWorker.ONE_TIME_NAME)
        assertEquals("REPLACE must leave exactly one request, not stack a second", 1, infos.size)
        assertNotEquals(
            "the forced request must actually replace the queued one, not be dropped behind it",
            unforcedId,
            infos.single().id,
        )
        assertFalse(
            "the replacement must carry the forced (no-charging) constraints, not the ones it replaced",
            infos.single().constraints.requiresCharging(),
        )
    }

    /** The converse of the above: an unforced call must not cancel a forced refresh in flight. */
    @Test
    fun `an unforced reconcileNow does not replace a forced one already queued`() = runTest {
        scheduler.reconcileNow(force = true)
        val forcedId = workInfosFor(ReconciliationWorker.ONE_TIME_NAME).single().id

        scheduler.reconcileNow(force = false)

        val infos = workInfosFor(ReconciliationWorker.ONE_TIME_NAME)
        assertEquals(1, infos.size)
        assertEquals(
            "KEEP must not let a later unforced call cancel a forced refresh already queued",
            forcedId,
            infos.single().id,
        )
    }

    /**
     * The gap in the two tests above: a *second* forced call is exactly as capable of colliding
     * with the *first* forced one as an unforced call is. The Settings "Full achievement refresh"
     * button has no debounce of its own, so a double-tap — or any second tap while a long refresh
     * is still running — must not cancel the in-flight pass and restart it from the top.
     */
    @Test
    fun `a repeated forced reconcileNow does not cancel and restart an already-queued forced one`() = runTest {
        scheduler.reconcileNow(force = true)
        val firstId = workInfosFor(ReconciliationWorker.ONE_TIME_NAME).single().id

        scheduler.reconcileNow(force = true)

        val infos = workInfosFor(ReconciliationWorker.ONE_TIME_NAME)
        assertEquals("a second forced tap must not stack a duplicate request", 1, infos.size)
        assertEquals(
            "a second forced tap while the first is still queued must not cancel and restart it",
            firstId,
            infos.single().id,
        )
    }

    @Test
    fun `ensurePeriodicReconciliation is idempotent — a second call keeps the first request`() {
        scheduler.ensurePeriodicReconciliation()
        val firstId = workInfosFor(ReconciliationWorker.PERIODIC_NAME).single().id

        scheduler.ensurePeriodicReconciliation()

        val infos = workInfosFor(ReconciliationWorker.PERIODIC_NAME)
        assertEquals("KEEP must not enqueue a second periodic request", 1, infos.size)
        assertEquals(firstId, infos.single().id)
    }

    @Test
    fun `wishlist price sampling waits for charging and unmetered network`() {
        scheduler.ensurePeriodicWishlistSampling()

        val info = workInfosFor(WishlistPriceSamplingWorker.PERIODIC_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        // Nobody is waiting for a price history sample, so it must never cost mobile data or
        // battery — the same bargain the reconciliation pass makes.
        assertTrue(info.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `wishlist price sampling is idempotent and has its own work name`() {
        scheduler.ensurePeriodicWishlistSampling()
        val firstId = workInfosFor(WishlistPriceSamplingWorker.PERIODIC_NAME).single().id
        scheduler.ensurePeriodicReconciliation()

        scheduler.ensurePeriodicWishlistSampling()

        val infos = workInfosFor(WishlistPriceSamplingWorker.PERIODIC_NAME)
        assertEquals("KEEP must not enqueue a second periodic request", 1, infos.size)
        assertEquals(firstId, infos.single().id)
        assertEquals(1, workInfosFor(ReconciliationWorker.PERIODIC_NAME).size)
    }

    @Test
    fun `syncNow enqueues under its own name with connectivity constraints`() = runTest {
        scheduler.syncNow()
        workManager.getWorkInfosForUniqueWorkFlow(SteamSyncWorker.ONE_TIME_NAME).first { it.isNotEmpty() }

        val info = workInfosFor(SteamSyncWorker.ONE_TIME_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `periodic and manual sync requests carry distinct trigger inputs`() = runTest {
        scheduler.ensurePeriodicSync()
        scheduler.syncNow()
        workManager.getWorkInfosForUniqueWorkFlow(SteamSyncWorker.ONE_TIME_NAME).first { it.isNotEmpty() }

        assertEquals(
            SteamSyncWorker.TRIGGER_PERIODIC,
            inputFor(SteamSyncWorker.UNIQUE_PERIODIC_NAME)
                .getString(SteamSyncWorker.KEY_TRIGGER),
        )
        assertEquals(
            SteamSyncWorker.TRIGGER_MANUAL,
            inputFor(SteamSyncWorker.ONE_TIME_NAME)
                .getString(SteamSyncWorker.KEY_TRIGGER),
        )
    }

    @Test
    fun `syncNow does not collide with the periodic Steam sync's work name`() = runTest {
        scheduler.ensurePeriodicSync()
        scheduler.syncNow()
        workManager.getWorkInfosForUniqueWorkFlow(SteamSyncWorker.ONE_TIME_NAME).first { it.isNotEmpty() }

        assertEquals(1, workInfosFor(SteamSyncWorker.UNIQUE_PERIODIC_NAME).size)
        assertEquals(1, workInfosFor(SteamSyncWorker.ONE_TIME_NAME).size)
    }

    @Test
    fun `operation views independently reattach to overlapping queued jobs`() = runTest {
        scheduler.syncNow()
        scheduler.reconcileNow(force = true)
        val steam = scheduler.steamOperation.first { it != null }!!
        val achievements = scheduler.achievementOperation.first { it != null }!!
        assertNotEquals(steam.id, achievements.id)
        assertEquals(com.example.backlogium.domain.OperationStatus.QUEUED, steam.status)
        assertEquals(com.example.backlogium.domain.OperationStatus.QUEUED, achievements.status)
        val recreated = SyncScheduler(RuntimeEnvironment.getApplication(), schedulerScope)
        assertEquals(steam.id, recreated.steamOperation.first { it != null }?.id)
        assertEquals(achievements.id, recreated.achievementOperation.first { it != null }?.id)
    }

    @Test
    fun `overlapping scheduled retry remains primary over a completed manual sync`() = runTest {
        OverlapOperationWorker.reset()
        val generation = AccountChangeMarkerStore(RuntimeEnvironment.getApplication()).activeGeneration.first()!!
        val periodic = PeriodicWorkRequestBuilder<OverlapOperationWorker>(15, TimeUnit.MINUTES)
            .addTag("setup-account:$generation")
            .setInputData(workDataOf(
                OP_ACCOUNT to generation,
                OverlapOperationWorker.ROLE to OverlapOperationWorker.PERIODIC,
            ))
            .build()
        workManager.enqueueUniquePeriodicWork(
            SteamSyncWorker.UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic,
        ).await()
        WorkManagerTestInitHelper.getTestDriver(RuntimeEnvironment.getApplication())!!
            .setPeriodDelayMet(periodic.id)
        val periodicExecutionId = OverlapOperationWorker.periodicStarted.await()

        val manual = OneTimeWorkRequestBuilder<OverlapOperationWorker>()
            .addTag("setup-account:$generation")
            .addTag("operation-requested:200")
            .setInputData(workDataOf(
                OP_ACCOUNT to generation,
                OverlapOperationWorker.ROLE to OverlapOperationWorker.MANUAL,
            ))
            .build()
        workManager.enqueueUniqueWork(SteamSyncWorker.ONE_TIME_NAME, ExistingWorkPolicy.KEEP, manual).await()
        OverlapOperationWorker.manualStarted.await()
        assertEquals(WorkInfo.State.RUNNING,
            workManager.getWorkInfoByIdFlow(manual.id).first { it?.state == WorkInfo.State.RUNNING }?.state)
        OverlapOperationWorker.manualGate.complete(Unit)
        workManager.getWorkInfoByIdFlow(manual.id).first { it?.state == WorkInfo.State.SUCCEEDED }
        assertEquals(WorkInfo.State.RUNNING,
            workManager.getWorkInfoByIdFlow(periodic.id).first { it?.state == WorkInfo.State.RUNNING }?.state)

        val inFlight = scheduler.steamOperation.first {
            it?.id == periodicExecutionId && it.status == OperationStatus.RUNNING
        }!!
        assertEquals(2, inFlight.counts?.processed)
        assertEquals(8, inFlight.counts?.total)
        assertTrue("the still-running scheduled operation keeps the sync action disabled",
            scheduler.syncInProgress.first())

        OverlapOperationWorker.periodicFirstAttemptGate.complete(Unit)
        val backingOff = workManager.getWorkInfoByIdFlow(periodic.id).first {
            it?.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0
        }!!
        assertEquals(WorkInfo.State.ENQUEUED, backingOff.state)
        val retry = scheduler.steamOperation.first { it?.status == OperationStatus.RETRYING }!!
        assertEquals(periodicExecutionId, retry.id)
        assertEquals(2, retry.counts?.processed)

        WorkManagerTestInitHelper.getTestDriver(RuntimeEnvironment.getApplication())!!
            .setInitialDelayMet(periodic.id)
        OverlapOperationWorker.retryStarted.await()
        val retried = scheduler.steamOperation.first {
            it?.id == periodicExecutionId && it.status == OperationStatus.RUNNING && it.counts?.processed == 4
        }!!
        assertEquals(8, retried.counts?.total)
        OverlapOperationWorker.periodicRetryGate.complete(Unit)
        OverlapOperationWorker.periodicFinished.await()
        workManager.getWorkInfoByIdFlow(periodic.id).first {
            it?.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0
        }

        val terminal = scheduler.steamOperation.first { it?.status == OperationStatus.SUCCEEDED }!!
        assertEquals("the later-started manual result is selected only after all active work ends",
            OverlapOperationWorker.manualExecutionId, terminal.id)
        assertEquals(5, terminal.counts?.processed)
    }

    @Test
    fun `setup correlates execution progress to its exact WorkManager request`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val generation = AccountChangeMarkerStore(context).activeGeneration.first()!!
        HeldOperationProgressWorker.gate = CompletableDeferred()
        val request = OneTimeWorkRequestBuilder<HeldOperationProgressWorker>()
            .addTag("setup-account:$generation")
            .setInputData(workDataOf(
                OP_ACCOUNT to generation,
                HeldOperationProgressWorker.EXECUTION_ID to "$generation:execution",
            ))
            .build()
        workManager.enqueue(request).await()

        try {
            val operation = scheduler.operationForWork(request.id.toString())
                .first { it?.status == OperationStatus.RUNNING }!!
            val stageId = SetupStageRegistry.STAGE_LIBRARY_SYNC
            val row = setupStagesUi(
                stages = listOf(fakeStage(stageId)),
                selection = setOf(stageId),
                run = SetupRunState(
                    loaded = true,
                    running = true,
                    pending = setOf(stageId),
                    workIds = mapOf(stageId to request.id.toString()),
                ),
                libraryOperation = operation,
            ).single()

            assertEquals("$generation:execution", row.operation?.id)
            assertEquals(request.id.toString(), row.operation?.workId)
        } finally {
            HeldOperationProgressWorker.gate.complete(Unit)
            workManager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
        }
    }

    private fun workInfosFor(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun inputFor(name: String) = WorkManagerImpl.getInstance()!!
        .workDatabase.workSpecDao()
        .getWorkSpec(workInfosFor(name).single().id.toString())!!
        .input
}

class HeldOperationProgressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setProgress(operationData(
            id = id.toString(),
            generation = inputData.getLong(OP_ACCOUNT, 0L),
            phase = OperationPhase.ACHIEVEMENTS,
            counts = OperationCounts(2, 4),
            executionId = inputData.getString(EXECUTION_ID),
            startedAt = 1234L,
        ))
        gate.await()
        return Result.success()
    }

    companion object {
        const val EXECUTION_ID = "execution_id"
        var gate = CompletableDeferred<Unit>()
    }
}

class OverlapOperationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val role = inputData.getString(ROLE) ?: error("Missing operation role")
        val source = if (role == PERIODIC) OperationSource.PERIODIC else OperationSource.MANUAL
        val generation = inputData.getLong(OP_ACCOUNT, 0L)
        val store = OperationReportStore(applicationContext)
        val report = store.begin(OperationChannel.STEAM, source, id.toString(), generation, runAttemptCount > 0)
        val counts = when {
            role == MANUAL -> OperationCounts(5, 5)
            runAttemptCount == 0 -> OperationCounts(2, 8)
            else -> OperationCounts(4, 8)
        }
        val progress = report.progress.copy(
            phase = OperationPhase.ACHIEVEMENTS,
            counts = counts,
        )
        setProgress(operationData(
            id.toString(), generation, OperationPhase.ACHIEVEMENTS, counts,
            executionId = progress.id, startedAt = report.startedAt,
        ))

        if (role == MANUAL) {
            manualExecutionId = progress.id
            manualStarted.complete(progress.id)
            manualGate.await()
        } else if (runAttemptCount == 0) {
            periodicStarted.complete(progress.id)
            periodicFirstAttemptGate.await()
            store.save(OperationChannel.STEAM, report.copy(
                progress = progress.copy(status = OperationStatus.RETRYING),
            ))
            return Result.retry()
        } else {
            retryStarted.complete(progress.id)
            periodicRetryGate.await()
        }

        store.save(OperationChannel.STEAM, report.copy(
            progress = progress.copy(status = OperationStatus.SUCCEEDED),
        ))
        if (role == PERIODIC) periodicFinished.complete(Unit)
        return Result.success(operationData(
            id.toString(), generation, OperationPhase.ACHIEVEMENTS, counts,
            executionId = progress.id, startedAt = report.startedAt,
        ))
    }

    companion object {
        const val ROLE = "overlap_role"
        const val PERIODIC = "periodic"
        const val MANUAL = "manual"

        lateinit var periodicStarted: CompletableDeferred<String>
        lateinit var manualStarted: CompletableDeferred<String>
        lateinit var retryStarted: CompletableDeferred<String>
        lateinit var periodicFinished: CompletableDeferred<Unit>
        lateinit var periodicFirstAttemptGate: CompletableDeferred<Unit>
        lateinit var manualGate: CompletableDeferred<Unit>
        lateinit var periodicRetryGate: CompletableDeferred<Unit>
        var manualExecutionId: String? = null

        fun reset() {
            periodicStarted = CompletableDeferred()
            manualStarted = CompletableDeferred()
            retryStarted = CompletableDeferred()
            periodicFinished = CompletableDeferred()
            periodicFirstAttemptGate = CompletableDeferred()
            manualGate = CompletableDeferred()
            periodicRetryGate = CompletableDeferred()
            manualExecutionId = null
        }
    }
}
