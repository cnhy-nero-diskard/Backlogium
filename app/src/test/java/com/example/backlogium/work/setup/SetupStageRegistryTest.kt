package com.example.backlogium.work.setup

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.work.HltbRefreshWorker
import com.example.backlogium.work.SteamAssetDownloadWorker
import com.example.backlogium.work.SteamSyncWorker
import com.example.backlogium.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
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
 * The stages this build actually registers, over a real [WorkManager].
 *
 * The id assertions are not tautologies: stage ids are persisted, so renaming one orphans every
 * user's stored opt-in and outcome for that stage. Pinning them here makes that a deliberate,
 * visible act rather than a rename that looks free.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SetupStageRegistryTest {

    private lateinit var workManager: WorkManager
    private lateinit var registry: SetupStageRegistry
    private lateinit var scheduler: SyncScheduler
    private lateinit var schedulerScope: CoroutineScope

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scheduler = SyncScheduler(context, schedulerScope)
        registry = SetupStageRegistry(context, scheduler)
    }

    @After
    fun tearDown() {
        schedulerScope.cancel()
    }

    @Test
    fun stageIdsArePinnedBecauseTheyArePersisted() {
        assertEquals(
            listOf(
                SetupStageRegistry.STAGE_LIBRARY_SYNC,
                SetupStageRegistry.STAGE_STEAM_ASSETS,
                SetupStageRegistry.STAGE_COMPLETION_TIMES,
            ),
            registry.stages.map { it.id },
        )
    }

    @Test
    fun theLibrarySyncRunsInScreenAndIsTheOnlyDefault() {
        val sync = registry.stages.single { it.id == SetupStageRegistry.STAGE_LIBRARY_SYNC }
        assertEquals(SetupStageExecution.IN_SCREEN, sync.execution)
        // Ticked: it is fast, and the app is meaningless without it.
        assertTrue(sync.defaultOptIn)

        // The expensive two are unticked, so someone setting up on mobile data has to choose them.
        registry.stages.filterNot { it.id == SetupStageRegistry.STAGE_LIBRARY_SYNC }
            .forEach { stage ->
                assertFalse("${stage.id} must not be ticked by default", stage.defaultOptIn)
                assertEquals(SetupStageExecution.DETACHED, stage.execution)
            }
    }

    @Test
    fun everyRegisteredStageIsAvailableAndOptional() {
        registry.stages.forEach { stage ->
            // add-offline-steam-assets has landed, so nothing is registered-but-unavailable here.
            assertNull("${stage.id} should be available in this build", stage.unavailableReason)
            assertTrue(stage.title.isNotBlank())
            assertTrue(stage.detail.isNotBlank())
        }
    }

    @Test
    fun decliningSetupOverTheRealRegistryStartsNoWork() = runTest {
        val store = FakeSetupStateStore()
        val coordinator = SetupCoordinator(registry, store, schedulerScope)

        coordinator.skipAll()

        registry.stages.forEach { stage ->
            assertEquals(SetupOutcome.Skipped, store.outcomes[stage.id])
        }
        assertTrue(workManager.enqueuedUnder(SteamSyncWorker.ONE_TIME_NAME).isEmpty())
        assertTrue(workManager.enqueuedUnder(SteamAssetDownloadWorker.UNIQUE_WORK_NAME).isEmpty())
        assertTrue(workManager.enqueuedUnder(HltbRefreshWorker.ONE_TIME_NAME).isEmpty())
    }

    @Test
    fun aStageStartedTwiceDoesNotStackDuplicateWork() {
        // Retry is re-running the stage's work, and the wrapped job's own unique name plus KEEP is
        // the whole of setup's concurrency story — this asserts that mechanism rather than a second
        // layer on top of it, which is exactly what the design refuses to add.
        scheduler.downloadSteamAssets(SteamAssetDownloadMode.DOWNLOAD_MISSING)
        scheduler.downloadSteamAssets(SteamAssetDownloadMode.DOWNLOAD_MISSING)

        assertEquals(1, workManager.enqueuedUnder(SteamAssetDownloadWorker.UNIQUE_WORK_NAME).size)
    }

    private fun WorkManager.enqueuedUnder(name: String) =
        getWorkInfosForUniqueWork(name).get()
}
