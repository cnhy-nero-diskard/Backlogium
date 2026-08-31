package com.example.backlogium.work.setup

import android.content.Context
import androidx.work.WorkManager
import com.example.backlogium.data.repo.HltbDatasetCheckResult
import com.example.backlogium.data.repo.HltbDatasetProgress
import com.example.backlogium.data.repo.HltbDatasetRepository
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.work.SteamAssetDownloadWorker
import com.example.backlogium.work.SteamSyncWorker
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three stages this build registers, in the order they run.
 *
 * Each one wraps work the app already performs and starts it through the same control Settings and
 * the Library use, so a stage's effects are indistinguishable from triggering that work directly.
 * The library sync in particular *is* `steam-sync`'s first-sync baseline poll — it gets that
 * behaviour by being that poll, not by reimplementing it, which is what keeps setup clear of the
 * invariant that the on-device engine is the sole author of derived values.
 *
 * **The default opt-ins encode cost.** Sync is ticked: it is fast, and the app is meaningless
 * without it. Artwork and completion times are unticked: one is measured in tens of megabytes and
 * the other in wall-clock time, and someone setting up on mobile data should have to choose them.
 */
@Singleton
class SetupStageRegistry @Inject constructor(
    @ApplicationContext context: Context,
    private val scheduler: SyncScheduler,
    private val hltbDatasetRepository: HltbDatasetRepository,
) : SetupStageSource {

    private val workManager: WorkManager = WorkManager.getInstance(context)

    override val stages: List<SetupStage> = listOf(
        SetupStage(
            id = STAGE_LIBRARY_SYNC,
            title = "Sync your Steam library",
            detail = "Fetches your games and playtime. Quick, and the app is empty without it.",
            defaultOptIn = true,
            execution = SetupStageExecution.IN_SCREEN,
            run = WorkStageRunner(
                workManager = workManager,
                uniqueWorkName = SteamSyncWorker.ONE_TIME_NAME,
                trigger = { scheduler.syncNow() },
                // The sync worker publishes no per-item progress, so this stage is deliberately
                // indeterminate rather than showing a total it does not have.
                progressOf = { null },
                failureReason = "Couldn't reach Steam. It will try again on its own; " +
                    "you can also re-run this from Settings.",
            ),
        ),
        SetupStage(
            id = STAGE_STEAM_ASSETS,
            title = "Download game artwork",
            detail = "Stores cover art on the device so the library renders offline. " +
                "Can be tens of megabytes.",
            defaultOptIn = false,
            execution = SetupStageExecution.DETACHED,
            run = WorkStageRunner(
                workManager = workManager,
                uniqueWorkName = SteamAssetDownloadWorker.UNIQUE_WORK_NAME,
                // Missing-only: setup is a first run, so there is nothing to refresh, and
                // re-downloading what is already stored would spend the user's data for nothing.
                trigger = { scheduler.downloadSteamAssets(SteamAssetDownloadMode.DOWNLOAD_MISSING) },
                progressOf = { data ->
                    SetupStageProgress(
                        processed = data.getInt(SteamAssetDownloadWorker.KEY_PROCESSED, 0),
                        total = data.getInt(SteamAssetDownloadWorker.KEY_TOTAL, 0),
                        // No label: this worker's own is the asset's CDN URL, which is not
                        // something to show a user — on device it wrapped to three lines of
                        // `media.steampowered.com/...jpg` and told them nothing. The count is the
                        // part that means anything here; the Settings asset card is where the
                        // per-item detail belongs.
                    )
                },
                failureReason = "The artwork download didn't finish. Re-run it from Settings.",
            ),
        ),
        SetupStage(
            id = STAGE_COMPLETION_TIMES,
            title = "Fetch completion times",
            detail = "Downloads the shared HowLongToBeat dataset so most of your library already " +
                "has completion times. One small download.",
            defaultOptIn = true,
            execution = SetupStageExecution.DETACHED,
            run = SetupStageRunner { onProgress ->
                when (val result = hltbDatasetRepository.checkAndApply(onProgress = { progress ->
                    onProgress(progress.toSetupStageProgress())
                })) {
                    is HltbDatasetCheckResult.Applied, is HltbDatasetCheckResult.UpToDate ->
                        SetupOutcome.Succeeded
                    is HltbDatasetCheckResult.Failed -> SetupOutcome.Failed(result.message)
                }
            },
        ),
    )

    companion object {
        /**
         * Persisted stage ids. Renaming one orphans every user's stored opt-in and outcome for that
         * stage — see [SetupStage].
         */
        const val STAGE_LIBRARY_SYNC = "library_sync"
        const val STAGE_STEAM_ASSETS = "steam_assets"
        const val STAGE_COMPLETION_TIMES = "completion_times"
    }
}

/** Adapts the dataset repository's own progress union onto the generic setup progress shape. */
private fun HltbDatasetProgress.toSetupStageProgress(): SetupStageProgress = when (this) {
    HltbDatasetProgress.Checking -> SetupStageProgress(
        processed = 0,
        total = 0,
        label = "Checking for a completion-times dataset",
    )
    is HltbDatasetProgress.Downloading -> SetupStageProgress(
        processed = bytesRead.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        total = (totalBytes ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        label = "Downloading completion times",
    )
    HltbDatasetProgress.Verifying -> SetupStageProgress(
        processed = 0,
        total = 0,
        label = "Verifying the dataset",
    )
    HltbDatasetProgress.Applying -> SetupStageProgress(
        processed = 0,
        total = 0,
        label = "Applying completion times",
    )
}
