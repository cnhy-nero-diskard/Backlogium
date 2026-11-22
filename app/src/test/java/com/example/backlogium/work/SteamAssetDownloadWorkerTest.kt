package com.example.backlogium.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.backlogium.data.local.dao.LibraryPollCommitDao
import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.dao.SteamAssetStoredSummary
import com.example.backlogium.data.local.dao.SteamGameImageSource
import com.example.backlogium.data.local.entity.LibraryPollCommit
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.entity.SteamAssetManifest
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.steamassets.SteamAssetRepository
import com.example.backlogium.data.steamassets.SteamAssetStore
import com.example.backlogium.work.setup.FakeSetupAccounts
import com.example.backlogium.work.setup.FakeSetupStateStore
import com.example.backlogium.work.setup.FakeStageRunner
import com.example.backlogium.work.setup.SetupCoordinator
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupStageActivity
import com.example.backlogium.work.setup.SetupStageExecution
import com.example.backlogium.work.setup.SetupStageProgress
import com.example.backlogium.work.setup.SetupStageRegistry
import com.example.backlogium.work.setup.SetupStageRunner
import com.example.backlogium.work.setup.SetupStageSource
import com.example.backlogium.work.setup.fakeStage
import com.example.backlogium.work.setup.fixtureSetupOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises [SteamAssetDownloadWorker.doWork] directly against a real [SteamAssetRepository]
 * wired to an in-memory fake [SteamAssetDao] — no Hilt entry point is needed since the worker's
 * `@AssistedInject` constructor is a plain Kotlin constructor callable directly.
 * [TestListenableWorkerBuilder] plus a hand-rolled [WorkerFactory] gives the worker a real,
 * fully-wired `WorkerParameters` (progress/foreground updaters included) without going through
 * WorkManager scheduling at all, so `setProgress`/`setForeground` inside `doWork()` are exercised
 * for real rather than stubbed out.
 *
 * The retry gate is covered with both a confirmed empty baseline and a pending sync followed by a
 * newly committed game inventory. A scripted HTTP 404 keeps that path independent of the network
 * and image decoder while proving every newly inventoried URL is processed.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned like ActivityVisibilityTrackerTest: the default/highest Robolectric shadow SDK is
// missing Context.getSystemService(Class), which doWork()'s setForeground() path calls into.
@Config(sdk = [35])
class SteamAssetDownloadWorkerTest {

    private fun buildWorker(
        repository: SteamAssetRepository,
        inputData: Data = Data.EMPTY,
        libraryPollCommits: LibraryPollCommitDao = confirmedEmptyBaseline(),
        credentials: CredentialsProvider = fixtureCredentials,
    ): SteamAssetDownloadWorker {
        val context = RuntimeEnvironment.getApplication()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = SteamAssetDownloadWorker(appContext, workerParameters, repository, libraryPollCommits, credentials)
        }
        return TestListenableWorkerBuilder<SteamAssetDownloadWorker>(context, inputData = inputData)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `doWork accepts a confirmed empty library baseline`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val assetDao = FakeSteamAssetDao()
        val repository = SteamAssetRepository(assetDao, SteamAssetStore(context), OkHttpClient())
        val commits = FakeLibraryPollCommitDao().apply {
            insert(LibraryPollCommit("empty-baseline", "poll", fixtureSetupOwner.steamId, 1L, gameCount = 0))
        }
        val worker = buildWorker(repository, libraryPollCommits = commits)

        val result = worker.doWork()

        assertTrue("a confirmed empty baseline may complete with no assets", result is ListenableWorker.Result.Success)
        assertTrue(assetDao.lastSavedRun != null)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `artwork stays pending while library sync retries then processes the committed games`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val assetDao = FakeSteamAssetDao()
        val requests = AtomicInteger()
        val repository = SteamAssetRepository(
            assetDao,
            SteamAssetStore(context),
            OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("fixture unavailable")
                    .body(ByteArray(0).toResponseBody())
                    .build()
            }.build(),
        )
        val commits = FakeLibraryPollCommitDao()
        val firstArtworkAttempt = CompletableDeferred<ListenableWorker.Result>()
        val retryArtwork = CompletableDeferred<Unit>()
        val completedArtworkAttempt = CompletableDeferred<ListenableWorker.Result>()
        fun worker() = buildWorker(repository, libraryPollCommits = commits)
        val library = FakeStageRunner().emitting(
            SetupStageProgress(0, 0, activity = SetupStageActivity.RETRY_BACKOFF),
        ).also { it.autoComplete = false }
        val artwork = object : SetupStageRunner {
            override suspend fun run(onProgress: (SetupStageProgress) -> Unit): SetupOutcome =
                error("runRequest owns this fixture's WorkManager retries")

            override suspend fun runRequest(
                requestId: String,
                accountGeneration: Long,
                onProgress: (SetupStageProgress) -> Unit,
                onWorkStarted: suspend (String) -> Unit,
            ): SetupOutcome {
                onWorkStarted("artwork-$requestId")
                firstArtworkAttempt.complete(worker().doWork())
                onProgress(SetupStageProgress(0, 0, activity = SetupStageActivity.RETRY_BACKOFF))
                retryArtwork.await()
                val result = worker().doWork()
                completedArtworkAttempt.complete(result)
                return if (result is ListenableWorker.Result.Success) SetupOutcome.Succeeded
                else SetupOutcome.Failed("Artwork retry did not complete")
            }
        }
        val registeredStages = listOf(
            fakeStage(SetupStageRegistry.STAGE_LIBRARY_SYNC, library),
            fakeStage(SetupStageRegistry.STAGE_STEAM_ASSETS, artwork, execution = SetupStageExecution.DETACHED),
            fakeStage(SetupStageRegistry.STAGE_COMPLETION_TIMES, execution = SetupStageExecution.DETACHED),
        )
        val coordinator = SetupCoordinator(
            source = object : SetupStageSource {
                override val stages = registeredStages
            },
            store = FakeSetupStateStore(),
            accounts = FakeSetupAccounts(),
            scope = backgroundScope,
        ).also {
            it.ensureLoaded()
            runCurrent()
        }

        coordinator.start(setOf(
            SetupStageRegistry.STAGE_LIBRARY_SYNC,
            SetupStageRegistry.STAGE_STEAM_ASSETS,
            SetupStageRegistry.STAGE_COMPLETION_TIMES,
        ))
        runCurrent()

        assertTrue(firstArtworkAttempt.await() is ListenableWorker.Result.Retry)
        assertTrue(coordinator.state.value.finished)
        assertTrue(SetupStageRegistry.STAGE_LIBRARY_SYNC in coordinator.state.value.pending)
        assertEquals(
            SetupStageActivity.RETRY_BACKOFF,
            coordinator.state.value.stageProgress[SetupStageRegistry.STAGE_LIBRARY_SYNC]?.activity,
        )
        assertTrue(SetupStageRegistry.STAGE_STEAM_ASSETS in coordinator.state.value.pending)
        assertEquals(SetupOutcome.NeverRun, coordinator.state.value.outcomes[SetupStageRegistry.STAGE_STEAM_ASSETS])
        assertEquals(SetupOutcome.Succeeded, coordinator.state.value.outcomes[SetupStageRegistry.STAGE_COMPLETION_TIMES])
        assertEquals(0, requests.get())
        assertNull(assetDao.lastSavedRun)

        assetDao.setGames(listOf(SteamGameImageSource(440L, "https://example.test/icon-440.jpg")))
        commits.insert(LibraryPollCommit("library-baseline", "poll", fixtureSetupOwner.steamId, 2L, gameCount = 1))
        retryArtwork.complete(Unit)
        runCurrent()

        assertTrue(completedArtworkAttempt.await() is ListenableWorker.Result.Success)
        assertEquals(6, assetDao.lastSavedRun?.unavailableCount)
        assertEquals(6, requests.get())
        assertEquals(SetupOutcome.Succeeded, coordinator.state.value.outcomes[SetupStageRegistry.STAGE_STEAM_ASSETS])

        library.gate.complete(Unit)
        runCurrent()
        assertEquals(SetupOutcome.Succeeded, coordinator.state.value.outcomes[SetupStageRegistry.STAGE_LIBRARY_SYNC])
    }

    @Test
    fun `doWork defaults to DOWNLOAD_MISSING when the mode input is missing`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dao = FakeSteamAssetDao()
        val repository = SteamAssetRepository(dao, SteamAssetStore(context), OkHttpClient())
        // No KEY_MODE / KEY_STARTED_AT in the input data at all.
        val worker = buildWorker(repository, inputData = Data.EMPTY)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            "an empty-inventory run must still persist a completed download-state row",
            "DOWNLOAD_MISSING",
            dao.lastSavedRun?.mode,
        )
    }

    @Test
    fun `doWork tolerates an invalid mode string by falling back to DOWNLOAD_MISSING`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dao = FakeSteamAssetDao()
        val repository = SteamAssetRepository(dao, SteamAssetStore(context), OkHttpClient())
        val worker = buildWorker(
            repository,
            inputData = workDataOf(SteamAssetDownloadWorker.KEY_MODE to "NOT_A_REAL_MODE"),
        )

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("DOWNLOAD_MISSING", dao.lastSavedRun?.mode)
    }

    // --- Pure progress-data tests: no WorkManager or repository required. ---

    @Test
    fun `progress workData round-trips every KEY_ constant`() {
        val data = workDataOf(
            SteamAssetDownloadWorker.KEY_PROCESSED to 3,
            SteamAssetDownloadWorker.KEY_TOTAL to 10,
            SteamAssetDownloadWorker.KEY_CURRENT_LABEL to "https://example.com/icon.jpg",
            SteamAssetDownloadWorker.KEY_STORED to 1,
            SteamAssetDownloadWorker.KEY_ALREADY_PRESENT to 1,
            SteamAssetDownloadWorker.KEY_UNAVAILABLE to 1,
            SteamAssetDownloadWorker.KEY_FAILED to 0,
        )

        assertEquals(3, data.getInt(SteamAssetDownloadWorker.KEY_PROCESSED, -1))
        assertEquals(10, data.getInt(SteamAssetDownloadWorker.KEY_TOTAL, -1))
        assertEquals("https://example.com/icon.jpg", data.getString(SteamAssetDownloadWorker.KEY_CURRENT_LABEL))
        assertEquals(1, data.getInt(SteamAssetDownloadWorker.KEY_STORED, -1))
        assertEquals(1, data.getInt(SteamAssetDownloadWorker.KEY_ALREADY_PRESENT, -1))
        assertEquals(1, data.getInt(SteamAssetDownloadWorker.KEY_UNAVAILABLE, -1))
        assertEquals(0, data.getInt(SteamAssetDownloadWorker.KEY_FAILED, -1))
    }

    @Test
    fun `processed never exceeds total across a simulated run`() {
        val total = 5
        var processed = 0
        val snapshots = mutableListOf<Data>()

        repeat(total) {
            processed += 1
            snapshots += workDataOf(
                SteamAssetDownloadWorker.KEY_PROCESSED to processed,
                SteamAssetDownloadWorker.KEY_TOTAL to total,
            )
        }

        snapshots.forEach { data ->
            val p = data.getInt(SteamAssetDownloadWorker.KEY_PROCESSED, -1)
            val t = data.getInt(SteamAssetDownloadWorker.KEY_TOTAL, -1)
            assertTrue("processed ($p) must never exceed total ($t)", p <= t)
        }
        assertEquals(total, snapshots.last().getInt(SteamAssetDownloadWorker.KEY_PROCESSED, -1))
    }
}

/** In-memory fake of the Room DAO — lets [SteamAssetRepository] run without a real database. */
private class FakeSteamAssetDao(
    private val avatarUrl: String? = null,
    private var games: List<SteamGameImageSource> = emptyList(),
    private val achievementIcons: List<String> = emptyList(),
) : SteamAssetDao {
    private val manifests = mutableMapOf<String, SteamAssetManifest>()
    var lastSavedRun: SteamAssetDownloadState? = null
        private set

    fun setGames(games: List<SteamGameImageSource>) {
        this.games = games
    }

    override suspend fun get(url: String): SteamAssetManifest? = manifests[url]
    override fun observeAll(): Flow<List<SteamAssetManifest>> = flowOf(manifests.values.toList())
    override suspend fun getAll(): List<SteamAssetManifest> = manifests.values.toList()
    override suspend fun upsert(manifest: SteamAssetManifest) {
        manifests[manifest.normalizedUrl] = manifest
    }

    override suspend fun invalidate(url: String) {
        manifests.remove(url)
    }

    override fun observeStoredSummary(): Flow<SteamAssetStoredSummary> = flowOf(SteamAssetStoredSummary(0, 0L))
    override fun observeHasInventory(): Flow<Boolean> = flowOf(false)
    override fun observeLastRun(): Flow<SteamAssetDownloadState?> = flowOf(lastSavedRun)
    override suspend fun gameImageSources(): List<SteamGameImageSource> = games
    override suspend fun profileAvatarUrl(): String? = avatarUrl
    override suspend fun achievementIconUrls(): List<String> = achievementIcons
    override suspend fun saveLastRun(state: SteamAssetDownloadState) {
        lastSavedRun = state
    }
}

private class FakeLibraryPollCommitDao(initialCommits: List<LibraryPollCommit> = emptyList()) : LibraryPollCommitDao {
    private val commits = initialCommits.associateBy { it.operationId }.toMutableMap()

    override suspend fun insert(commit: LibraryPollCommit) {
        commits[commit.operationId] = commit
    }

    override suspend fun get(operationId: String, steamId: String): LibraryPollCommit? =
        commits[operationId]?.takeIf { it.steamId == steamId }

    override suspend fun unfinalized(workId: String, steamId: String): LibraryPollCommit? =
        commits.values.firstOrNull { it.workId == workId && it.steamId == steamId && !it.finalized }

    override suspend fun finalize(operationId: String) {
        commits[operationId]?.let { commits[operationId] = it.copy(finalized = true) }
    }

    override suspend fun hasBaseline(steamId: String): Boolean = commits.values.any { it.steamId == steamId }

    override fun observeBaseline(steamId: String): Flow<Boolean> = flowOf(commits.values.any { it.steamId == steamId })

    override suspend fun clear() = commits.clear()
}

private fun confirmedEmptyBaseline() = FakeLibraryPollCommitDao(
    listOf(LibraryPollCommit("confirmed-empty", "poll", fixtureSetupOwner.steamId, 1L, gameCount = 0)),
)

private val fixtureCredentials = object : CredentialsProvider {
    override suspend fun currentCredentials() = CredentialsState.Configured("fixture-key", fixtureSetupOwner.steamId)
}
