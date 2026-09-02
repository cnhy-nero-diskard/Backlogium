package com.example.backlogium.work.setup

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.hltb.HltbDatasetArtifactStore
import com.example.backlogium.data.hltb.HltbDatasetConnectivity
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.HltbDatasetDao
import com.example.backlogium.data.local.dao.HltbDatasetSnapshotRow
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDatasetLength
import com.example.backlogium.data.local.entity.HltbDatasetMapping
import com.example.backlogium.data.local.entity.HltbDatasetState
import com.example.backlogium.data.repo.HltbDatasetRepository
import com.example.backlogium.data.repo.HltbLibraryCatalog
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.data.updates.GitHubReleaseApi
import com.example.backlogium.data.updates.GitHubReleaseDto
import com.example.backlogium.data.updates.UpdateDownloader
import com.example.backlogium.data.updates.UpdateVerifier
import com.example.backlogium.work.SteamAssetDownloadWorker
import com.example.backlogium.work.SteamSyncWorker
import com.example.backlogium.work.SyncScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
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
import retrofit2.Response

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
        registry = SetupStageRegistry(context, scheduler, unusedHltbDatasetRepository())
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
    fun theLibrarySyncRunsInScreenAndOptsInByDefault() {
        val sync = registry.stages.single { it.id == SetupStageRegistry.STAGE_LIBRARY_SYNC }
        assertEquals(SetupStageExecution.IN_SCREEN, sync.execution)
        // Ticked: it is fast, and the app is meaningless without it.
        assertTrue(sync.defaultOptIn)
    }

    @Test
    fun completionTimesOptsInByDefaultButArtworkDoesNot() {
        // Completion times is one small dataset download, unlike the whole-library sweep it
        // replaced — see decouple-hltb-fetching's design. Artwork remains unticked: it is
        // measured in tens of megabytes, and someone setting up on mobile data should choose it.
        val completionTimes = registry.stages.single { it.id == SetupStageRegistry.STAGE_COMPLETION_TIMES }
        val assets = registry.stages.single { it.id == SetupStageRegistry.STAGE_STEAM_ASSETS }
        assertEquals(SetupStageExecution.DETACHED, completionTimes.execution)
        assertEquals(SetupStageExecution.DETACHED, assets.execution)
        assertTrue(completionTimes.defaultOptIn)
        assertFalse(assets.defaultOptIn)
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

    /**
     * A syntactically valid [HltbDatasetRepository] that no test here ever calls into — these
     * tests cover stage registration and the WorkManager-backed stages, not the dataset download
     * itself, which [HltbDatasetRepositoryTest] already covers.
     */
    private fun unusedHltbDatasetRepository(): HltbDatasetRepository = HltbDatasetRepository(
        releaseApi = object : GitHubReleaseApi {
            override suspend fun latestRelease(): GitHubReleaseDto = error("unused")
            override suspend fun releases(perPage: Int, page: Int): List<GitHubReleaseDto> = error("unused")
            override suspend fun structuredNotes(url: String): Response<ResponseBody> = error("unused")
        },
        downloader = object : UpdateDownloader {
            override suspend fun download(
                url: String,
                destination: File,
                onProgress: suspend (Long, Long?) -> Unit,
            ) = error("unused")
            override suspend fun fetchText(url: String): String = error("unused")
        },
        verifier = object : UpdateVerifier {
            override suspend fun hasMatchingDigest(apk: File, checksumAsset: String) = error("unused")
            override fun hasMatchingSigner(apk: File) = error("unused")
        },
        artifacts = object : HltbDatasetArtifactStore {
            override fun stagingFile(): File = error("unused")
            override fun clearStaging() = error("unused")
        },
        connectivity = HltbDatasetConnectivity { error("unused") },
        datasetDao = object : HltbDatasetDao {
            override fun observeSnapshot(): Flow<List<HltbDatasetSnapshotRow>> = flowOf(emptyList())
            override suspend fun getSnapshot(): List<HltbDatasetSnapshotRow> = emptyList()
            override suspend fun getState(): HltbDatasetState? = null
            override fun observeAllRows(): Flow<List<HltbData>> = flowOf(emptyList())
            override suspend fun getAllRows(): List<HltbData> = emptyList()
            override suspend fun getRow(): HltbData? = null
            override suspend fun getRow(appId: Long): HltbData? = null
            override suspend fun upsert(state: HltbDatasetState) = error("unused")
            override suspend fun upsertMappings(rows: List<HltbDatasetMapping>) = error("unused")
            override suspend fun upsertLengths(rows: List<HltbDatasetLength>) = error("unused")
            override suspend fun deleteMappings() = error("unused")
            override suspend fun deleteLengths() = error("unused")
        },
        hltbDataDao = object : HltbDataDao {
            override suspend fun upsert(data: HltbData) = error("unused")
            override suspend fun upsertAll(data: List<HltbData>) = error("unused")
            override suspend fun deleteDatasetRows() = error("unused")
            override suspend fun getByAppId(appId: Long): HltbData? = null
            override fun observeAll(): Flow<List<HltbData>> = flowOf(emptyList())
            override suspend fun getAll(): List<HltbData> = emptyList()
            override fun observeAllWithDataset(): Flow<List<HltbData>> = flowOf(emptyList())
            override suspend fun getAllWithDataset(): List<HltbData> = emptyList()
            override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(emptyList())
            override fun observeMatchCenter(): Flow<List<HltbData>> = flowOf(emptyList())
            override suspend fun getMatchCenter(): List<HltbData> = emptyList()
            override suspend fun markNeedsReviewWithBroaderCandidates(appId: Long, candidatesJson: String): Int = 0
        },
        libraryCatalog = HltbLibraryCatalog { emptySet() },
        transaction = object : DatabaseTransactionScope {
            override suspend fun <R> run(block: suspend () -> R): R = block()
        },
    )
}
