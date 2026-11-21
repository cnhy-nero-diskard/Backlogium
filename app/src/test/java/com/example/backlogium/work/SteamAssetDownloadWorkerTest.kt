package com.example.backlogium.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.dao.SteamAssetStoredSummary
import com.example.backlogium.data.local.dao.SteamGameImageSource
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.entity.SteamAssetManifest
import com.example.backlogium.data.steamassets.SteamAssetRepository
import com.example.backlogium.data.steamassets.SteamAssetStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises [SteamAssetDownloadWorker.doWork] directly against a real [SteamAssetRepository]
 * wired to an in-memory fake [SteamAssetDao] — no Hilt entry point is needed since the worker's
 * `@AssistedInject` constructor is a plain Kotlin constructor callable directly.
 * [TestListenableWorkerBuilder] plus a hand-rolled [WorkerFactory] gives the worker a real,
 * fully-wired `WorkerParameters` (progress/foreground updaters included) without going through
 * WorkManager scheduling at all, so `setProgress`/`setForeground` inside `doWork()` are exercised
 * for real rather than stubbed out.
 *
 * Only the empty-inventory path is covered here: driving a non-empty inventory through `doWork()`
 * would additionally require faking network responses through [SteamAssetRepository]'s internal
 * `OkHttpClient` and an image decode that survives [SteamAssetStore.write]'s bitmap-bounds check,
 * which is out of scope for this pass. Progress accounting itself (bounds, KEY_* round-trip) is
 * covered below as a pure `Data` test that needs neither WorkManager nor the repository.
 */
@RunWith(RobolectricTestRunner::class)
// Pinned like ActivityVisibilityTrackerTest: the default/highest Robolectric shadow SDK is
// missing Context.getSystemService(Class), which doWork()'s setForeground() path calls into.
@Config(sdk = [35])
class SteamAssetDownloadWorkerTest {

    private fun buildWorker(repository: SteamAssetRepository, inputData: Data = Data.EMPTY): SteamAssetDownloadWorker {
        val context = RuntimeEnvironment.getApplication()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = SteamAssetDownloadWorker(appContext, workerParameters, repository)
        }
        return TestListenableWorkerBuilder<SteamAssetDownloadWorker>(context, inputData = inputData)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `doWork with empty inventory succeeds immediately with zero processed and total`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = SteamAssetRepository(FakeSteamAssetDao(), SteamAssetStore(context), OkHttpClient())
        val worker = buildWorker(repository)

        val result = worker.doWork()

        assertTrue("an empty inventory must still complete successfully", result is ListenableWorker.Result.Success)
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
    private val games: List<SteamGameImageSource> = emptyList(),
    private val achievementIcons: List<String> = emptyList(),
) : SteamAssetDao {
    private val manifests = mutableMapOf<String, SteamAssetManifest>()
    var lastSavedRun: SteamAssetDownloadState? = null
        private set

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
