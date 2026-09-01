package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.hltb.HLTB_DATASET_ASSET_NAME
import com.example.backlogium.data.hltb.HLTB_DATASET_CHECKSUM_ASSET_NAME
import com.example.backlogium.data.hltb.HltbContributionFormatter
import com.example.backlogium.data.hltb.HltbContributionPreparation
import com.example.backlogium.data.hltb.HltbDataset
import com.example.backlogium.data.hltb.HltbDatasetArtifactStore
import com.example.backlogium.data.hltb.HltbDatasetCodec
import com.example.backlogium.data.hltb.HltbDatasetConnectivity
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.HltbDatasetDao
import com.example.backlogium.data.local.dao.HltbDatasetSnapshotRow
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbDatasetLength
import com.example.backlogium.data.local.entity.HltbDatasetMapping
import com.example.backlogium.data.local.entity.HltbDatasetState
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.updates.GitHubReleaseApi
import com.example.backlogium.data.updates.GitHubReleaseAssetDto
import com.example.backlogium.data.updates.GitHubReleaseDto
import com.example.backlogium.data.updates.UpdateDownloader
import com.example.backlogium.data.updates.UpdateVerifier
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class HltbDatasetRepositoryTest {
    @Test
    fun applyingDatasetIsAtomicAndHonorsEveryPrecedenceRule() = runTest {
        val gatheredAt = 2_000L
        val payload = payload(
            version = 2,
            gatheredAt = gatheredAt,
            mappings = "[1,10],[2,20],[3,99],[4,30],[6,60]",
            lengths = "[10,100,null,null,null],[30,null,null,300,null],[60,600,null,null,null]",
        )
        val harness = harness(
            releasePayload = payload,
            rows = listOf(
                row(1, 11, HltbMatchStatus.RESOLVED, HltbDataOrigin.AUTOMATIC, main = 50),
                row(2, null, HltbMatchStatus.NEEDS_REVIEW, HltbDataOrigin.AUTOMATIC),
                row(3, 30, HltbMatchStatus.RESOLVED, HltbDataOrigin.MANUAL),
                row(5, 50, HltbMatchStatus.RESOLVED, HltbDataOrigin.DATASET),
                row(6, null, HltbMatchStatus.UNMATCHED, HltbDataOrigin.AUTOMATIC),
            ),
            libraryIds = setOf(1, 2, 3, 6),
        )
        try {
            val result = harness.repository.checkAndApply()

            assertTrue(result is HltbDatasetCheckResult.Applied)
            assertEquals(2, (result as HltbDatasetCheckResult.Applied).gamesGainingLengths)
            assertEquals(
                row(1, 10, HltbMatchStatus.RESOLVED, HltbDataOrigin.DATASET, main = 100, at = gatheredAt),
                harness.hltb.rows.getValue(1L),
            )
            val correspondenceOnly = harness.hltb.rows.getValue(2L)
            assertEquals(HltbMatchStatus.RESOLVED, correspondenceOnly.matchStatus)
            assertEquals(HltbDataOrigin.DATASET, correspondenceOnly.origin)
            assertNull(correspondenceOnly.mainStoryMinutes)

            val manual = harness.hltb.rows.getValue(3L)
            assertEquals(30L, manual.hltbId)
            assertEquals(HltbDataOrigin.MANUAL, manual.origin)
            assertEquals(300, manual.completionistMinutes)
            assertEquals(gatheredAt, manual.fetchedAt)

            assertEquals(HltbDataOrigin.DATASET, harness.hltb.rows.getValue(6L).origin)
            assertEquals(60L, harness.hltb.rows.getValue(6L).hltbId)
            assertNull(harness.hltb.rows[5L])
            assertEquals(2L, harness.dataset.state.value?.datasetVersion)
            assertEquals(
                mapOf(1L to 10L, 2L to 20L, 3L to 99L, 4L to 30L, 6L to 60L),
                harness.dataset.mappings,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun datasetAgreementWithManualResolutionKeepsManualChoiceAndRefreshesLengths() = runTest {
        val gatheredAt = 2_000L
        val harness = harness(
            releasePayload = payload(
                version = 2,
                gatheredAt = gatheredAt,
                mappings = "[3,30]",
                lengths = "[30,100,200,300,400]",
            ),
            rows = listOf(
                row(
                    appId = 3,
                    hltbId = 30,
                    status = HltbMatchStatus.RESOLVED,
                    origin = HltbDataOrigin.MANUAL,
                    main = 10,
                ),
            ),
            libraryIds = setOf(3),
        )
        try {
            assertTrue(harness.repository.checkAndApply() is HltbDatasetCheckResult.Applied)

            val applied = harness.hltb.rows.getValue(3L)
            assertEquals(30L, applied.hltbId)
            assertEquals(HltbDataOrigin.MANUAL, applied.origin)
            assertEquals(HltbMatchStatus.RESOLVED, applied.matchStatus)
            assertEquals(100, applied.mainStoryMinutes)
            assertEquals(200, applied.mainExtraMinutes)
            assertEquals(300, applied.completionistMinutes)
            assertEquals(400, applied.allStylesMinutes)
            assertEquals(gatheredAt, applied.fetchedAt)
            assertNull(applied.candidatesJson)
        } finally {
            harness.close()
        }
    }

    @Test
    fun alreadyCurrentSkipsDownloadAndDoesNotRejuvenateRows() = runTest {
        val currentPayload = payload(2, 2_000L, "[1,10]", "[10,100,null,null,null]")
        val existing = row(
            appId = 1,
            hltbId = 10,
            status = HltbMatchStatus.RESOLVED,
            origin = HltbDataOrigin.DATASET,
            main = 100,
            at = 2_000L,
        )
        val harness = harness(
            releasePayload = currentPayload,
            currentPayload = currentPayload,
            rows = listOf(existing),
        )
        try {
            val result = harness.repository.checkAndApply()

            assertTrue(result is HltbDatasetCheckResult.UpToDate)
            assertEquals(0, harness.downloader.downloadCalls)
            assertEquals(existing, harness.hltb.rows.getValue(1L))
        } finally {
            harness.close()
        }
    }

    @Test
    fun downloadAndVerificationFailuresLeaveHeldStateUntouched() = runTest {
        val currentPayload = payload(1, 1_000L, "[9,90]", "[90,90,null,null,null]")
        val nextPayload = payload(2, 2_000L, "[1,10]", "[10,100,null,null,null]")
        listOf(
            harness(
                releasePayload = nextPayload,
                currentPayload = currentPayload,
                rows = listOf(row(9, 90, HltbMatchStatus.RESOLVED, HltbDataOrigin.DATASET, main = 90)),
                downloadFailure = IOException("offline"),
            ) to HltbDatasetFailureStage.DOWNLOAD,
            harness(
                releasePayload = nextPayload,
                currentPayload = currentPayload,
                rows = listOf(row(9, 90, HltbMatchStatus.RESOLVED, HltbDataOrigin.DATASET, main = 90)),
                digestMatches = false,
            ) to HltbDatasetFailureStage.VERIFICATION,
        ).forEach { (harness, expectedStage) ->
            try {
                val beforeRows = harness.hltb.rows.toMap()

                val result = harness.repository.checkAndApply()

                assertTrue(result is HltbDatasetCheckResult.Failed)
                assertEquals(expectedStage, (result as HltbDatasetCheckResult.Failed).stage)
                assertEquals(beforeRows, harness.hltb.rows)
                assertEquals(1L, harness.dataset.state.value?.datasetVersion)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun interruptedApplicationRollsBackRowsAndDatasetState() = runTest {
        val currentPayload = payload(1, 1_000L, "[9,90]", "[90,90,null,null,null]")
        val nextPayload = payload(2, 2_000L, "[1,10]", "[10,100,null,null,null]")
        val existing = row(9, 90, HltbMatchStatus.RESOLVED, HltbDataOrigin.DATASET, main = 90)
        val harness = harness(
            releasePayload = nextPayload,
            currentPayload = currentPayload,
            rows = listOf(existing),
            failDuringUpsert = true,
            libraryIds = setOf(1),
        )
        try {
            val result = harness.repository.checkAndApply()

            assertTrue(result is HltbDatasetCheckResult.Failed)
            assertEquals(
                HltbDatasetFailureStage.APPLICATION,
                (result as HltbDatasetCheckResult.Failed).stage,
            )
            assertEquals(mapOf(9L to existing), harness.hltb.rows)
            assertEquals(1L, harness.dataset.state.value?.datasetVersion)
        } finally {
            harness.close()
        }
    }

    @Test
    fun readingAppliedDataNeverChecksTheReleaseService() = runTest {
        val currentPayload = payload(1, 1_000L, "[7,70]", "[70,700,null,null,null]")
        val harness = harness(releasePayload = currentPayload, currentPayload = currentPayload)
        try {
            val state = harness.repository.appliedState.first()
            val found = harness.repository.find(7L)

            assertEquals(1L, state?.datasetVersion)
            assertEquals(70L, found?.hltbId)
            assertEquals(HltbDataOrigin.DATASET, found?.origin)
            assertEquals(0, harness.api.releaseCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun oneHundredAppReleasesDoNotHideDatasetReleaseOnSecondPage() = runTest {
        val releasePayload = payload(2, 2_000L, "[1,10]", "[10,100,null,null,null]")
        val harness = harness(
            releasePayload = releasePayload,
            libraryIds = setOf(1),
            releases = List(100) { index ->
                GitHubReleaseDto(tagName = "v1.0.$index")
            } + release(2, releasePayload),
        )
        try {
            val result = harness.repository.checkAndApply()

            assertTrue(result is HltbDatasetCheckResult.Applied)
            assertEquals(2L, (result as HltbDatasetCheckResult.Applied).dataset.datasetVersion)
            assertEquals(2, harness.api.releaseCalls)
        } finally {
            harness.close()
        }
    }

    @Test
    fun offlineCheckDoesNotCallReleaseServiceOrChangeEmptyState() = runTest {
        val harness = harness(
            releasePayload = payload(1, 1_000L, "[1,10]", "[10,100,null,null,null]"),
            libraryIds = setOf(1),
            online = false,
        )
        try {
            val result = harness.repository.checkAndApply()

            assertTrue(result is HltbDatasetCheckResult.Failed)
            assertEquals(
                HltbDatasetFailureStage.CHECK,
                (result as HltbDatasetCheckResult.Failed).stage,
            )
            assertEquals(0, harness.api.releaseCalls)
            assertEquals(0, harness.downloader.downloadCalls)
            assertNull(harness.dataset.state.value)
            assertTrue(harness.hltb.rows.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun concurrentChecksShareStagingSafelyAndDownloadOnlyOnce() = runTest {
        val harness = harness(
            releasePayload = payload(1, 1_000L, "[1,10]", "[10,100,null,null,null]"),
            libraryIds = setOf(1),
            pauseDownload = true,
        )
        try {
            val first = async { harness.repository.checkAndApply() }
            harness.downloader.started.await()
            val second = async { harness.repository.checkAndApply() }
            runCurrent()

            assertEquals(1, harness.downloader.downloadCalls)
            harness.downloader.resume.complete(Unit)
            assertTrue(first.await() is HltbDatasetCheckResult.Applied)
            assertTrue(second.await() is HltbDatasetCheckResult.UpToDate)
            assertEquals(1, harness.downloader.downloadCalls)
        } finally {
            harness.downloader.resume.complete(Unit)
            harness.close()
        }
    }

    @Test
    fun nonLibraryMappingStaysInPayloadLookupButNotCacheOrContributionExport() = runTest {
        val releasePayload = payload(
            version = 2,
            gatheredAt = 2_000L,
            mappings = "[1,10],[50,501],[99,990]",
            lengths = "[10,100,null,null,null],[501,501,null,null,null],[990,990,null,null,null]",
        )
        val harness = harness(
            releasePayload = releasePayload,
            rows = listOf(
                row(50, 500, HltbMatchStatus.UNMATCHED, HltbDataOrigin.AUTOMATIC),
            ),
            libraryIds = setOf(1),
        )
        try {
            assertTrue(harness.repository.checkAndApply() is HltbDatasetCheckResult.Applied)

            assertEquals(10L, harness.hltb.rows[1L]?.hltbId)
            assertEquals(501L, harness.hltb.rows[50L]?.hltbId)
            assertEquals(HltbDataOrigin.DATASET, harness.hltb.rows[50L]?.origin)
            assertNull(harness.hltb.rows[99L])
            assertEquals(
                mapOf(1L to 10L, 50L to 501L, 99L to 990L),
                harness.dataset.mappings,
            )
            assertEquals(990L, harness.repository.find(99L)?.hltbId)

            val export = HltbContributionFormatter.prepare(
                cachedRows = harness.hltb.getAll(),
                datasetRows = harness.repository.getAll(),
                ownedAppIds = setOf(1L),
            )
                as HltbContributionPreparation.Ready
            assertEquals(1, export.mappingCount)
            assertTrue(!export.contents.contains("[50,501]"))
            assertTrue(!export.contents.contains("[99,990]"))
        } finally {
            harness.close()
        }
    }

    private fun harness(
        releasePayload: String,
        currentPayload: String? = null,
        rows: List<HltbData> = emptyList(),
        libraryIds: Set<Int> = emptySet(),
        downloadFailure: Exception? = null,
        digestMatches: Boolean = true,
        failDuringUpsert: Boolean = false,
        online: Boolean = true,
        pauseDownload: Boolean = false,
        releases: List<GitHubReleaseDto>? = null,
    ): Harness {
        val root = createTempDir(prefix = "backlogium-hltb-dataset")
        val datasetDao = FakeHltbDatasetDao(currentPayload?.let { HltbDatasetCodec.decode(it) })
        val hltbDao = FakeHltbDataDao(rows, failDuringUpsert)
        val api = FakeReleaseApi(
            releases ?: listOf(release(releasePayload.datasetVersion(), releasePayload)),
        )
        val downloader = FakeDownloader(releasePayload, downloadFailure, pauseDownload)
        val artifacts = FakeArtifacts(root)
        val transaction = SnapshotTransactionScope(hltbDao, datasetDao)
        val repository = HltbDatasetRepository(
            releaseApi = api,
            downloader = downloader,
            verifier = FakeVerifier(digestMatches),
            artifacts = artifacts,
            connectivity = HltbDatasetConnectivity { online },
            datasetDao = datasetDao,
            hltbDataDao = hltbDao,
            libraryCatalog = HltbLibraryCatalog { libraryIds.mapTo(mutableSetOf()) { it.toLong() } },
            transaction = transaction,
        )
        return Harness(repository, api, downloader, datasetDao, hltbDao, root)
    }

    private fun payload(
        version: Long,
        gatheredAt: Long,
        mappings: String,
        lengths: String,
    ) = """
        {
          "schemaVersion": 1,
          "datasetVersion": $version,
          "gatheredAt": $gatheredAt,
          "mappings": [$mappings],
          "lengths": [$lengths]
        }
    """.trimIndent()

    private fun String.datasetVersion(): Long =
        Regex("\"datasetVersion\"\\s*:\\s*(\\d+)").find(this)!!.groupValues[1].toLong()

    private fun row(
        appId: Long,
        hltbId: Long?,
        status: HltbMatchStatus,
        origin: HltbDataOrigin,
        main: Int? = null,
        at: Long = 500L,
    ) = HltbData(
        appId = appId,
        hltbId = hltbId,
        mainStoryMinutes = main,
        fetchedAt = at,
        matchStatus = status,
        candidatesJson = if (status == HltbMatchStatus.NEEDS_REVIEW) "[]" else null,
        origin = origin,
    )

    private fun release(version: Long, payload: String) = GitHubReleaseDto(
        tagName = "hltb-dataset-v$version",
        assets = listOf(
            GitHubReleaseAssetDto(
                HLTB_DATASET_ASSET_NAME,
                "https://example.test/$HLTB_DATASET_ASSET_NAME",
                payload.toByteArray().size.toLong(),
            ),
            GitHubReleaseAssetDto(
                HLTB_DATASET_CHECKSUM_ASSET_NAME,
                "https://example.test/$HLTB_DATASET_CHECKSUM_ASSET_NAME",
                64L,
            ),
        ),
    )

    private data class Harness(
        val repository: HltbDatasetRepository,
        val api: FakeReleaseApi,
        val downloader: FakeDownloader,
        val dataset: FakeHltbDatasetDao,
        val hltb: FakeHltbDataDao,
        val root: File,
    ) {
        fun close() {
            root.deleteRecursively()
        }
    }

    private class FakeReleaseApi(private val releases: List<GitHubReleaseDto>) : GitHubReleaseApi {
        var releaseCalls = 0

        override suspend fun latestRelease(): GitHubReleaseDto = error("unused")

        override suspend fun releases(perPage: Int, page: Int): List<GitHubReleaseDto> {
            releaseCalls++
            return releases.drop((page - 1) * perPage).take(perPage)
        }

        override suspend fun structuredNotes(url: String): Response<ResponseBody> = error("unused")
    }

    private class FakeDownloader(
        private val payload: String,
        private val failure: Exception?,
        private val pauseDownload: Boolean,
    ) : UpdateDownloader {
        var downloadCalls = 0
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: suspend (Long, Long?) -> Unit,
        ) {
            downloadCalls++
            started.complete(Unit)
            if (pauseDownload) resume.await()
            failure?.let { throw it }
            destination.parentFile?.mkdirs()
            destination.writeText(payload)
            onProgress(destination.length(), destination.length())
        }

        override suspend fun fetchText(url: String): String = "checksum"
    }

    private class FakeVerifier(private val matches: Boolean) : UpdateVerifier {
        override suspend fun hasMatchingDigest(apk: File, checksumAsset: String): Boolean = matches
        override fun hasMatchingSigner(apk: File): Boolean = error("dataset must not verify APK signer")
    }

    private class FakeArtifacts(root: File) : HltbDatasetArtifactStore {
        private val staging = File(root, "incoming.json")
        override fun stagingFile(): File = staging
        override fun clearStaging() {
            staging.delete()
            File(staging.absolutePath + ".part").delete()
        }
    }

    /** Mirrors the normalized dataset tables: one metadata row plus separate mapping/length maps. */
    private class FakeHltbDatasetDao(initial: HltbDataset? = null) : HltbDatasetDao {
        val state = MutableStateFlow(
            initial?.let {
                HltbDatasetState(
                    schemaVersion = it.schemaVersion,
                    datasetVersion = it.datasetVersion,
                    gatheredAt = it.gatheredAt,
                )
            },
        )
        val mappings = mutableMapOf<Long, Long>().apply { initial?.mappings?.let(::putAll) }
        val lengths = mutableMapOf<Long, HltbDatasetLength>().apply {
            initial?.lengths?.forEach { (hltbId, cells) ->
                put(
                    hltbId,
                    HltbDatasetLength(
                        hltbId = hltbId,
                        mainStoryMinutes = cells.mainStoryMinutes,
                        mainExtraMinutes = cells.mainExtraMinutes,
                        completionistMinutes = cells.completionistMinutes,
                        allStylesMinutes = cells.allStylesMinutes,
                    ),
                )
            }
        }

        override fun observeSnapshot(): Flow<List<HltbDatasetSnapshotRow>> = state.map { snapshotRows(it) }
        override suspend fun getSnapshot(): List<HltbDatasetSnapshotRow> = snapshotRows(state.value)
        override suspend fun getState(): HltbDatasetState? = state.value

        override fun observeAllRows(): Flow<List<HltbData>> = state.map { datasetRows() }
        override suspend fun getAllRows(): List<HltbData> = datasetRows()
        override suspend fun getRow(): HltbData? = datasetRows().firstOrNull()
        override suspend fun getRow(appId: Long): HltbData? = datasetRows().firstOrNull { it.appId == appId }

        override suspend fun upsert(state: HltbDatasetState) {
            this.state.value = state
        }

        override suspend fun upsertMappings(rows: List<HltbDatasetMapping>) {
            rows.forEach { mappings[it.appId] = it.hltbId }
        }

        override suspend fun upsertLengths(rows: List<HltbDatasetLength>) {
            rows.forEach { lengths[it.hltbId] = it }
        }

        override suspend fun deleteMappings() {
            mappings.clear()
        }

        override suspend fun deleteLengths() {
            lengths.clear()
        }

        private fun snapshotRows(metadata: HltbDatasetState?): List<HltbDatasetSnapshotRow> {
            if (metadata == null) return emptyList()
            if (mappings.isEmpty()) {
                return listOf(
                    HltbDatasetSnapshotRow(metadata.schemaVersion, metadata.datasetVersion, metadata.gatheredAt, null),
                )
            }
            return mappings.keys.sorted().map { appId ->
                HltbDatasetSnapshotRow(metadata.schemaVersion, metadata.datasetVersion, metadata.gatheredAt, appId)
            }
        }

        private fun datasetRows(): List<HltbData> {
            val metadata = state.value ?: return emptyList()
            return mappings.entries.sortedBy { it.key }.map { (appId, hltbId) ->
                val cells = lengths[hltbId]
                HltbData(
                    appId = appId,
                    hltbId = hltbId,
                    mainStoryMinutes = cells?.mainStoryMinutes,
                    mainExtraMinutes = cells?.mainExtraMinutes,
                    completionistMinutes = cells?.completionistMinutes,
                    allStylesMinutes = cells?.allStylesMinutes,
                    fetchedAt = metadata.gatheredAt,
                    matchStatus = HltbMatchStatus.RESOLVED,
                    candidatesJson = null,
                    origin = HltbDataOrigin.DATASET,
                )
            }
        }

        fun snapshot() = DatasetSnapshot(state.value, mappings.toMap(), lengths.toMap())

        fun restore(snapshot: DatasetSnapshot) {
            state.value = snapshot.state
            mappings.clear()
            mappings.putAll(snapshot.mappings)
            lengths.clear()
            lengths.putAll(snapshot.lengths)
        }

        data class DatasetSnapshot(
            val state: HltbDatasetState?,
            val mappings: Map<Long, Long>,
            val lengths: Map<Long, HltbDatasetLength>,
        )
    }

    private class FakeHltbDataDao(
        initial: List<HltbData>,
        private val failDuringUpsert: Boolean,
    ) : HltbDataDao {
        val rows = initial.associateByTo(mutableMapOf(), HltbData::appId)

        override suspend fun upsert(data: HltbData) {
            rows[data.appId] = data
        }

        override suspend fun upsertAll(data: List<HltbData>) {
            data.forEachIndexed { index, row ->
                rows[row.appId] = row
                if (failDuringUpsert && index == 0) throw IOException("interrupted")
            }
        }

        override suspend fun deleteDatasetRows() {
            rows.values.removeAll { it.origin == HltbDataOrigin.DATASET }
        }

        override suspend fun getByAppId(appId: Long): HltbData? = rows[appId]
        override fun observeAll(): Flow<List<HltbData>> = flowOf(rows.values.toList())
        override suspend fun getAll(): List<HltbData> = rows.values.toList()
        override fun observeAllWithDataset(): Flow<List<HltbData>> = flowOf(rows.values.toList())
        override suspend fun getAllWithDataset(): List<HltbData> = rows.values.toList()
        override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(
            rows.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW },
        )
        override fun observeMatchCenter(): Flow<List<HltbData>> = flowOf(
            rows.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW || it.matchStatus == HltbMatchStatus.UNMATCHED },
        )
        override suspend fun getMatchCenter(): List<HltbData> =
            rows.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW || it.matchStatus == HltbMatchStatus.UNMATCHED }

        fun restore(snapshot: Map<Long, HltbData>) {
            rows.clear()
            rows.putAll(snapshot)
        }
    }

    private class SnapshotTransactionScope(
        private val hltb: FakeHltbDataDao,
        private val dataset: FakeHltbDatasetDao,
    ) : DatabaseTransactionScope {
        override suspend fun <R> run(block: suspend () -> R): R {
            val rowsBefore = hltb.rows.toMap()
            val datasetBefore = dataset.snapshot()
            return try {
                block()
            } catch (failure: Throwable) {
                hltb.restore(rowsBefore)
                dataset.restore(datasetBefore)
                throw failure
            }
        }
    }
}
