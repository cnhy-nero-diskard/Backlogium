package com.example.backlogium.work

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scheduler = SyncScheduler(context, schedulerScope)
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
    fun `syncNow enqueues under its own name with connectivity constraints`() {
        scheduler.syncNow()

        val info = workInfosFor(SteamSyncWorker.ONE_TIME_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `syncNow does not collide with the periodic Steam sync's work name`() {
        scheduler.ensurePeriodicSync()
        scheduler.syncNow()

        assertEquals(1, workInfosFor(SteamSyncWorker.UNIQUE_PERIODIC_NAME).size)
        assertEquals(1, workInfosFor(SteamSyncWorker.ONE_TIME_NAME).size)
    }

    @Test
    fun `hltb refresh exposes that an offline request is waiting for network`() = runTest {
        scheduler.refreshHltbNow(listOf(440L, 620L))

        assertEquals(HltbRefreshStatus.WAITING_FOR_NETWORK, scheduler.hltbRefreshStatus.first())
        assertTrue(scheduler.hltbRefreshInProgress.first())
    }

    @Test
    fun `first-attempt enqueued work is queued when validated network is available`() {
        assertEquals(
            HltbRefreshStatus.QUEUED,
            hltbRefreshStatusFor(
                hasRunning = false,
                hasRetrying = false,
                hasEnqueued = true,
                hasValidatedNetwork = true,
            ),
        )
        assertEquals(
            HltbRefreshStatus.WAITING_FOR_NETWORK,
            hltbRefreshStatusFor(
                hasRunning = false,
                hasRetrying = false,
                hasEnqueued = true,
                hasValidatedNetwork = false,
            ),
        )
    }

    @Test
    fun `persistent timeout only cancels a first attempt that is still offline`() {
        assertTrue(shouldCancelHltbRefresh(firstAttemptStillQueued = true, hasValidatedNetwork = false))
        assertFalse(shouldCancelHltbRefresh(firstAttemptStillQueued = true, hasValidatedNetwork = true))
        assertFalse(shouldCancelHltbRefresh(firstAttemptStillQueued = false, hasValidatedNetwork = false))
    }

    @Test
    fun `offline timeout starts at the connectivity transition rather than queue time`() {
        val offlineSince = 29_000L

        assertEquals(29_000L, hltbTimeoutDelayMillis(30_000L, offlineSince))
        assertEquals(1L, hltbTimeoutDelayMillis(58_999L, offlineSince))
        assertEquals(0L, hltbTimeoutDelayMillis(59_000L, offlineSince))
    }

    /**
     * A second refresh request while one is already pending is dropped by `KEEP` — and must leave
     * the offline window alone. Resetting it from the enqueue path used to hand the *original*
     * refresh a fresh 30 seconds every time the user tapped again, so a repeatedly-tapped refresh
     * could never time out.
     */
    @Test
    fun `a KEEP-dropped duplicate refresh does not restart the offline window`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        scheduler.refreshHltbNow(listOf(440L))
        val firstId = workInfosFor(HltbRefreshWorker.ONE_TIME_NAME).single().id

        // Robolectric has no validated internet, so the request sits WAITING_FOR_NETWORK and the
        // window is open. Rewind it by 20s to stand in for 20 seconds of waiting.
        assertEquals(HltbRefreshStatus.WAITING_FOR_NETWORK, scheduler.hltbRefreshStatus.first())
        val store = HltbOfflineWaitStore(context)
        val offlineSince = requireNotNull(store.offlineSince()) - 20_000L
        store.clear()
        store.markOffline(offlineSince)

        scheduler.refreshHltbNow(listOf(620L))

        assertEquals(
            "KEEP must drop the duplicate rather than replace the pending refresh",
            firstId,
            workInfosFor(HltbRefreshWorker.ONE_TIME_NAME).single().id,
        )
        assertEquals(
            "the dropped duplicate must not re-anchor the offline window to now",
            offlineSince,
            store.offlineSince(),
        )
        assertEquals(
            "the original refresh must keep its remaining ~10s, not get a fresh 30s",
            10_000L,
            hltbTimeoutDelayMillis(offlineSince + 20_000L, store.offlineSince()),
        )
    }

    private fun workInfosFor(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()
}
