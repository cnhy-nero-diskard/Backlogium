package com.example.backlogium.work

import androidx.work.Configuration
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * Covers [SyncScheduler]'s manual offline-asset download surface: enqueue idempotency under
 * [androidx.work.ExistingWorkPolicy.KEEP], the constraints attached to the request, cancellation,
 * and the pure [steamAssetStatusFor] mapping function. Follows [SyncSchedulerTest]'s pattern of a
 * real, in-memory [WorkManager] backed by [SynchronousExecutor] — Robolectric has no validated
 * network by default, so the enqueued request itself never progresses past `ENQUEUED`, which is
 * exactly the state these enqueue-focused tests need.
 */
@RunWith(RobolectricTestRunner::class)
class SteamAssetSchedulerTest {

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
    fun `downloadSteamAssets is idempotent — a duplicate tap keeps the first request`() {
        scheduler.downloadSteamAssets(SteamAssetDownloadMode.DOWNLOAD_MISSING)
        val firstId = workInfosFor(SteamAssetDownloadWorker.UNIQUE_WORK_NAME).single().id

        scheduler.downloadSteamAssets(SteamAssetDownloadMode.DOWNLOAD_MISSING)

        val infos = workInfosFor(SteamAssetDownloadWorker.UNIQUE_WORK_NAME)
        assertEquals("KEEP must not stack a second request under the same unique name", 1, infos.size)
        assertEquals(firstId, infos.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun `downloadSteamAssets requires connectivity and storage not low`() {
        scheduler.downloadSteamAssets(SteamAssetDownloadMode.DOWNLOAD_MISSING)

        val constraints = workInfosFor(SteamAssetDownloadWorker.UNIQUE_WORK_NAME).single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun `cancelSteamAssetDownload cancels the enqueued unique work`() {
        scheduler.downloadSteamAssets(SteamAssetDownloadMode.DOWNLOAD_MISSING)
        assertEquals(WorkInfo.State.ENQUEUED, workInfosFor(SteamAssetDownloadWorker.UNIQUE_WORK_NAME).single().state)

        scheduler.cancelSteamAssetDownload()

        assertEquals(WorkInfo.State.CANCELLED, workInfosFor(SteamAssetDownloadWorker.UNIQUE_WORK_NAME).single().state)
    }

    // --- steamAssetStatusFor: pure mapping from WorkInfo snapshots to SteamAssetDownloadStatus. ---

    @Test
    fun `steamAssetStatusFor maps RUNNING with a known total to RUNNING`() {
        val info = workInfo(WorkInfo.State.RUNNING, progress = workDataOf(SteamAssetDownloadWorker.KEY_TOTAL to 10))

        assertEquals(SteamAssetDownloadStatus.RUNNING, steamAssetStatusFor(listOf(info)))
    }

    @Test
    fun `steamAssetStatusFor maps RUNNING with no total yet to PREPARING`() {
        val info = workInfo(WorkInfo.State.RUNNING, progress = Data.EMPTY)

        assertEquals(SteamAssetDownloadStatus.PREPARING, steamAssetStatusFor(listOf(info)))
    }

    @Test
    fun `steamAssetStatusFor maps RUNNING with total 0 to PREPARING`() {
        val info = workInfo(WorkInfo.State.RUNNING, progress = workDataOf(SteamAssetDownloadWorker.KEY_TOTAL to 0))

        assertEquals(SteamAssetDownloadStatus.PREPARING, steamAssetStatusFor(listOf(info)))
    }

    @Test
    fun `steamAssetStatusFor maps ENQUEUED and BLOCKED to QUEUED`() {
        assertEquals(SteamAssetDownloadStatus.QUEUED, steamAssetStatusFor(listOf(workInfo(WorkInfo.State.ENQUEUED))))
        assertEquals(SteamAssetDownloadStatus.QUEUED, steamAssetStatusFor(listOf(workInfo(WorkInfo.State.BLOCKED))))
    }

    @Test
    fun `steamAssetStatusFor maps terminal FAILED and CANCELLED states through`() {
        assertEquals(SteamAssetDownloadStatus.FAILED, steamAssetStatusFor(listOf(workInfo(WorkInfo.State.FAILED))))
        assertEquals(SteamAssetDownloadStatus.CANCELLED, steamAssetStatusFor(listOf(workInfo(WorkInfo.State.CANCELLED))))
    }

    @Test
    fun `steamAssetStatusFor maps an empty list to IDLE`() {
        assertEquals(SteamAssetDownloadStatus.IDLE, steamAssetStatusFor(emptyList()))
    }

    @Test
    fun `steamAssetStatusFor maps SUCCEEDED with no other info to IDLE`() {
        // SUCCEEDED isn't RUNNING/ENQUEUED/BLOCKED/FAILED/CANCELLED, so it falls through to IDLE —
        // WorkManager clears a completed one-time request's WorkInfo once observers stop caring,
        // but while it lingers this must not be misread as an active failure/cancellation.
        assertEquals(SteamAssetDownloadStatus.IDLE, steamAssetStatusFor(listOf(workInfo(WorkInfo.State.SUCCEEDED))))
    }

    @Test
    fun `steamAssetStatusFor prioritizes a RUNNING entry over a stale terminal one in the same list`() {
        val infos = listOf(
            workInfo(WorkInfo.State.CANCELLED),
            workInfo(WorkInfo.State.RUNNING, progress = workDataOf(SteamAssetDownloadWorker.KEY_TOTAL to 4)),
        )

        assertEquals(SteamAssetDownloadStatus.RUNNING, steamAssetStatusFor(infos))
    }

    private fun workInfo(
        state: WorkInfo.State,
        progress: Data = Data.EMPTY,
    ): WorkInfo = WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY, progress, 0)

    private fun workInfosFor(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()
}
