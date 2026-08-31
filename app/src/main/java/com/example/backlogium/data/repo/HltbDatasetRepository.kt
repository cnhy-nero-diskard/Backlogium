package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.hltb.HltbDataset
import com.example.backlogium.data.hltb.HltbDatasetArtifactStore
import com.example.backlogium.data.hltb.HltbDatasetCodec
import com.example.backlogium.data.hltb.HltbDatasetConnectivity
import com.example.backlogium.data.hltb.HltbDatasetEntry
import com.example.backlogium.data.hltb.newestHltbDatasetRelease
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.HltbDatasetDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbDatasetState
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.updates.GitHubReleaseApi
import com.example.backlogium.data.updates.UpdateDownloader
import com.example.backlogium.data.updates.UpdateVerifier
import com.example.backlogium.data.updates.allReleases
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Dataset-only lookup seam used by [HltbRepository]'s cache -> dataset -> network order. */
fun interface HltbDatasetLookup {
    suspend fun find(appId: Long): HltbData?

    /** Full applied dataset decoded from local Room state; never performs network I/O. */
    suspend fun getAll(): List<HltbData> = emptyList()

    /** Observes the full applied dataset from local Room state; never performs network I/O. */
    fun observeAll(): Flow<List<HltbData>> = flowOf(emptyList())
}

data class AppliedHltbDataset(
    val schemaVersion: Int,
    val datasetVersion: Long,
    val gatheredAt: Long,
    val coveredAppIds: Set<Long>,
)

sealed interface HltbDatasetProgress {
    data object Checking : HltbDatasetProgress
    data class Downloading(val bytesRead: Long, val totalBytes: Long?) : HltbDatasetProgress
    data object Verifying : HltbDatasetProgress
    data object Applying : HltbDatasetProgress
}

enum class HltbDatasetFailureStage {
    CHECK,
    DOWNLOAD,
    VERIFICATION,
    APPLICATION,
}

sealed interface HltbDatasetCheckResult {
    data class Applied(
        val dataset: AppliedHltbDataset,
        val gamesGainingLengths: Int,
    ) : HltbDatasetCheckResult

    data class UpToDate(val dataset: AppliedHltbDataset?) : HltbDatasetCheckResult

    data class Failed(
        val stage: HltbDatasetFailureStage,
        val message: String,
    ) : HltbDatasetCheckResult
}

/**
 * Explicit dataset discovery, verified acquisition, and atomic application.
 *
 * Merely observing [appliedState] or using [find] reads Room only. [checkAndApply] is the sole
 * release-service entry point, so app startup and offline use never issue an implicit check.
 */
@Singleton
class HltbDatasetRepository @Inject constructor(
    private val releaseApi: GitHubReleaseApi,
    private val downloader: UpdateDownloader,
    private val verifier: UpdateVerifier,
    private val artifacts: HltbDatasetArtifactStore,
    private val connectivity: HltbDatasetConnectivity,
    private val datasetDao: HltbDatasetDao,
    private val hltbDataDao: HltbDataDao,
    private val libraryCatalog: HltbLibraryCatalog,
    private val transaction: DatabaseTransactionScope,
) : HltbDatasetLookup {
    private val checkMutex = Mutex()

    val appliedState: Flow<AppliedHltbDataset?> = datasetDao.observeState().map { state ->
        state?.decodeVerifiedState()?.toAppliedState()
    }

    override fun observeAll(): Flow<List<HltbData>> = datasetDao.observeState().map { state ->
        state?.decodeVerifiedState()?.toHltbDataRows().orEmpty()
    }

    override suspend fun getAll(): List<HltbData> =
        datasetDao.getState()?.decodeVerifiedState()?.toHltbDataRows().orEmpty()

    override suspend fun find(appId: Long): HltbData? =
        datasetDao.getState()
            ?.decodeVerifiedState()
            ?.entryFor(appId)
            ?.toHltbData()

    suspend fun checkAndApply(
        onProgress: suspend (HltbDatasetProgress) -> Unit = {},
    ): HltbDatasetCheckResult = checkMutex.withLock {
        checkAndApplyOnce(onProgress)
    }

    private suspend fun checkAndApplyOnce(
        onProgress: suspend (HltbDatasetProgress) -> Unit,
    ): HltbDatasetCheckResult {
        onProgress(HltbDatasetProgress.Checking)
        if (!connectivity.isOnline()) {
            return HltbDatasetCheckResult.Failed(
                HltbDatasetFailureStage.CHECK,
                "Dataset check requires a validated internet connection.",
            )
        }
        val release = try {
            releaseApi.allReleases().newestHltbDatasetRelease()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return failure.asResult(HltbDatasetFailureStage.CHECK, "Dataset check failed")
        } ?: return HltbDatasetCheckResult.UpToDate(datasetDao.getState()?.toAppliedState())

        val current = datasetDao.getState()
        if (current != null && release.version <= current.datasetVersion) {
            return HltbDatasetCheckResult.UpToDate(current.toAppliedState())
        }
        if (release.declaredSize > MAX_DATASET_BYTES) {
            return HltbDatasetCheckResult.Failed(
                HltbDatasetFailureStage.VERIFICATION,
                "Dataset exceeds the supported size.",
            )
        }

        artifacts.clearStaging()
        val staging = artifacts.stagingFile()
        try {
            try {
                downloader.download(release.datasetUrl, staging) { bytesRead, totalBytes ->
                    onProgress(HltbDatasetProgress.Downloading(bytesRead, totalBytes))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return failure.asResult(HltbDatasetFailureStage.DOWNLOAD, "Dataset download failed")
            }

            onProgress(HltbDatasetProgress.Verifying)
            if (staging.length() > MAX_DATASET_BYTES) {
                return HltbDatasetCheckResult.Failed(
                    HltbDatasetFailureStage.VERIFICATION,
                    "Dataset exceeds the supported size.",
                )
            }
            val checksum = try {
                downloader.fetchText(release.checksumUrl)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return failure.asResult(
                    HltbDatasetFailureStage.VERIFICATION,
                    "Dataset checksum download failed",
                )
            }
            val digestMatches = try {
                verifier.hasMatchingDigest(staging, checksum)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return failure.asResult(HltbDatasetFailureStage.VERIFICATION, "Dataset verification failed")
            }
            if (!digestMatches) {
                return HltbDatasetCheckResult.Failed(
                    HltbDatasetFailureStage.VERIFICATION,
                    "Dataset checksum did not match.",
                )
            }

            val payload = try {
                staging.readUtf8()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return failure.asResult(HltbDatasetFailureStage.VERIFICATION, "Dataset could not be read")
            }
            val dataset = try {
                HltbDatasetCodec.decode(payload)
            } catch (failure: Exception) {
                return failure.asResult(HltbDatasetFailureStage.VERIFICATION, "Dataset format is invalid")
            }
            if (dataset.datasetVersion != release.version) {
                return HltbDatasetCheckResult.Failed(
                    HltbDatasetFailureStage.VERIFICATION,
                    "Dataset version does not match ${release.tag}.",
                )
            }

            onProgress(HltbDatasetProgress.Applying)
            val summary = try {
                applyVerified(dataset, payload)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return failure.asResult(HltbDatasetFailureStage.APPLICATION, "Dataset application failed")
            }
            return HltbDatasetCheckResult.Applied(
                dataset = dataset.toAppliedState(),
                gamesGainingLengths = summary.gamesGainingLengths,
            )
        } finally {
            artifacts.clearStaging()
        }
    }

    private suspend fun applyVerified(
        dataset: HltbDataset,
        payload: String,
    ): HltbDatasetApplySummary = transaction.run {
        val existingRows = hltbDataDao.getAll()
        val existingByAppId = existingRows.associateBy(HltbData::appId)
        val libraryAppIds = libraryCatalog.appIds()
        val applicableAppIds = libraryAppIds + existingByAppId.keys
        val manualByAppId = existingRows
            .filter { it.origin == HltbDataOrigin.MANUAL }
            .associateBy(HltbData::appId)

        val datasetRows = dataset.mappings.mapNotNull { (appId, _) ->
            if (appId !in applicableAppIds || appId in manualByAppId) {
                null
            } else {
                dataset.entryFor(appId)?.toHltbData()
            }
        }
        val refreshedManualRows = manualByAppId.values.mapNotNull { manual ->
            val hltbId = manual.hltbId ?: return@mapNotNull null
            val lengths = dataset.lengths[hltbId] ?: return@mapNotNull null
            manual.copy(
                mainStoryMinutes = lengths.mainStoryMinutes,
                mainExtraMinutes = lengths.mainExtraMinutes,
                completionistMinutes = lengths.completionistMinutes,
                allStylesMinutes = lengths.allStylesMinutes,
                fetchedAt = dataset.gatheredAt,
                matchStatus = HltbMatchStatus.RESOLVED,
                candidatesJson = null,
            )
        }

        hltbDataDao.deleteDatasetRows()
        val appliedRows = datasetRows + refreshedManualRows
        if (appliedRows.isNotEmpty()) hltbDataDao.upsertAll(appliedRows)
        datasetDao.upsert(
            HltbDatasetState(
                schemaVersion = dataset.schemaVersion,
                datasetVersion = dataset.datasetVersion,
                gatheredAt = dataset.gatheredAt,
                payloadJson = payload,
            ),
        )

        val afterByAppId = existingRows
            .filterNot { it.origin == HltbDataOrigin.DATASET }
            .associateByTo(mutableMapOf(), HltbData::appId)
            .apply { appliedRows.forEach { put(it.appId, it) } }
        val gamesGainingLengths = libraryAppIds.count { appId ->
            !existingByAppId[appId].hasKnownLength() && afterByAppId[appId].hasKnownLength()
        }
        HltbDatasetApplySummary(gamesGainingLengths)
    }

    private fun HltbDatasetState.decodeVerifiedState(): HltbDataset? =
        runCatching { HltbDatasetCodec.decode(payloadJson) }
            .getOrNull()
            ?.takeIf {
                it.schemaVersion == schemaVersion &&
                    it.datasetVersion == datasetVersion &&
                    it.gatheredAt == gatheredAt
            }

    private fun HltbDatasetState.toAppliedState(): AppliedHltbDataset? =
        decodeVerifiedState()?.toAppliedState()

    private fun HltbDataset.toAppliedState() = AppliedHltbDataset(
        schemaVersion = schemaVersion,
        datasetVersion = datasetVersion,
        gatheredAt = gatheredAt,
        coveredAppIds = mappings.keys,
    )

    private fun HltbDataset.toHltbDataRows(): List<HltbData> =
        mappings.keys.mapNotNull { appId -> entryFor(appId)?.toHltbData() }

    private fun HltbDatasetEntry.toHltbData() = HltbData(
        appId = appId,
        hltbId = hltbId,
        mainStoryMinutes = lengths?.mainStoryMinutes,
        mainExtraMinutes = lengths?.mainExtraMinutes,
        completionistMinutes = lengths?.completionistMinutes,
        allStylesMinutes = lengths?.allStylesMinutes,
        fetchedAt = gatheredAt,
        matchStatus = HltbMatchStatus.RESOLVED,
        candidatesJson = null,
        origin = HltbDataOrigin.DATASET,
    )

    private suspend fun File.readUtf8(): String = withContext(Dispatchers.IO) {
        readText(Charsets.UTF_8)
    }

    private fun Exception.asResult(stage: HltbDatasetFailureStage, prefix: String) =
        HltbDatasetCheckResult.Failed(stage, message?.let { "$prefix: $it" } ?: prefix)

    private data class HltbDatasetApplySummary(val gamesGainingLengths: Int)

    private companion object {
        const val MAX_DATASET_BYTES = 16L * 1024 * 1024
    }
}

private fun HltbData?.hasKnownLength(): Boolean = this != null && (
    mainStoryMinutes != null || mainExtraMinutes != null ||
        completionistMinutes != null || allStylesMinutes != null
    )
