package com.example.backlogium.work.setup

import android.content.Context
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.LibraryPollRequest
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.domain.LibraryPollResult
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
 * Admission goes through the scheduler's `admit*` seams: the exact job (fresh or `KEEP`-reused) is
 * captured under a durable request identity, and a queued retry, an offline wait, a cancelled job,
 * or a pruned job each stays its own truthful state instead of a manufactured terminal failure.
 * The library stage's terminal result comes from the attributable Room record for the exact
 * admitted work/account ([ProfileRepository.libraryPollResult]) — WorkManager scheduler success
 * alone is never a library-sync success.
 *
 * **The default opt-ins encode cost.** Sync and completion times are ticked: the first makes the
 * app useful and the second is one small shared dataset. Artwork is unticked because it is
 * measured in tens of megabytes; completion-time application runs in wall-clock time, and
 * someone setting up on mobile data should have to choose the expensive option.
 */
@Singleton
class SetupStageRegistry private constructor(
    context: Context,
    private val scheduler: SyncScheduler,
    private val pollResult: suspend (String) -> LibraryPollResult,
) : SetupStageSource {

    @Inject constructor(
        @ApplicationContext context: Context,
        scheduler: SyncScheduler,
        profileRepository: ProfileRepository,
        credentials: CredentialsProvider,
    ) : this(context, scheduler, { workId ->
        val account = credentials.currentCredentials()?.steamId?.takeIf { it.isNotBlank() }
        if (account == null) LibraryPollResult.NotPerformed.MissingCredentials
        else profileRepository.libraryPollResult(LibraryPollRequest(workId, account))
    })

    /** Test/scheduler-only construction without library account evidence source. */
    constructor(context: Context, scheduler: SyncScheduler) : this(context, scheduler, { LibraryPollResult.Unknown })

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
                admitWork = scheduler::admitSyncNow,
                // The sync worker publishes no per-item progress, so this stage is deliberately
                // indeterminate rather than showing a total it does not have.
                progressOf = { null },
                failureReason = "Couldn't reach Steam. It will try again on its own; " +
                    "you can also re-run this from Settings.",
                classifyTerminal = { workId, info -> librarySyncTerminal(workId, info) },
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
                admitWork = { requestId -> scheduler.admitDownloadSteamAssets(requestId, SteamAssetDownloadMode.DOWNLOAD_MISSING) },
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
                classifyTerminal = { _, info ->
                    steamAssetsTerminalState("The artwork download didn't finish. Re-run it from Settings.")(info)
                },
            ),
        ),
        SetupStage(
            id = STAGE_COMPLETION_TIMES,
            title = "Fetch completion times",
            detail = "Downloads the shared HowLongToBeat dataset so most of your library already " +
                "has completion times. One small download.",
            defaultOptIn = true,
            execution = SetupStageExecution.DETACHED,
            run = WorkStageRunner(
                workManager = workManager,
                uniqueWorkName = HltbDatasetWorker.UNIQUE_WORK_NAME,
                admitWork = scheduler::admitEnsureCompletionTimes,
                progressOf = { data ->
                    data.getString(HltbDatasetWorker.KEY_LABEL)?.let { label ->
                        SetupStageProgress(
                            processed = data.getInt(HltbDatasetWorker.KEY_PROCESSED, 0),
                            total = data.getInt(HltbDatasetWorker.KEY_TOTAL, 0),
                            label = label,
                        )
                    }
                },
                failureReason = "Completion times didn't finish. Re-run it from Settings.",
            ),
        ),
    )

    /**
     * The library stage's attributable terminal classification for the exact admitted work.
     *
     * A finished poll is classified from the durable Room record for that exact work/account, never
     * from WorkManager success alone: committed (including confirmed empty) is a truthful success;
     * a not-performed reason stays attributable; a recoverable failure keeps its reason; an unknown
     * result (no evidence, or evidence for a different work/account) is an explicit recovery
     * request. Missing credentials refuse the classification instead of inventing a result, and a
     * cancelled job keeps its distinct default classification (this returns null for it).
     */
    private suspend fun librarySyncTerminal(workId: String, info: WorkInfo): SetupOperationState? = when (info.state) {
        WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> {
            when (val result = pollResult(workId)) {
                is LibraryPollResult.Committed -> SetupOperationState.Succeeded(
                    if (result.confirmedEmpty) {
                        "Your library is empty — that's a confirmed empty baseline."
                    } else {
                        null
                    },
                )
                is LibraryPollResult.NotPerformed -> SetupOperationState.Failed(libraryNotPerformedReason(result))
                is LibraryPollResult.RecoverableFailure -> SetupOperationState.Failed(result.reason)
                LibraryPollResult.Unknown -> SetupOperationState.RecoveryRequired(
                    "The library sync finished, but its result can't be verified. " +
                        "Request it again from Settings.",
                )
            }
        }
        else -> null
    }

    private fun libraryNotPerformedReason(result: LibraryPollResult.NotPerformed): String = when (result) {
        LibraryPollResult.NotPerformed.MissingCredentials ->
            "Steam isn't connected. Connect it in Settings first."
        LibraryPollResult.NotPerformed.AccountAdmissionRefused ->
            "Steam didn't accept this poll for the current account. Confirm the account change first."
        LibraryPollResult.NotPerformed.UnconfirmedEmpty ->
            "Steam returned an unreadable empty library — your profile may be private."
    }

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

/**
 * The artwork stage's terminal classification.
 *
 * The artwork worker persists a real inventory snapshot in [SteamAssetDownloadWorker.KEY_TOTAL]
 * even when empty, so:
 * - an explicit zero total is a *successful* zero-available-items result with an honest
 *   re-run-after-sync explanation — never a fabricated populated download, never a sibling failure;
 * - a `KEY_TOTAL` that is absent (a legacy finished job written before the key existed) cannot be
 *   classified: report recovery required with an explicit re-request rather than inventing
 *   zero games or claiming a full download;
 * - every other terminal state keeps its ordinary scheduler meaning.
 */
internal fun steamAssetsTerminalState(failureReason: String): (WorkInfo) -> SetupOperationState? =
    { info ->
        when (info.state) {
            WorkInfo.State.SUCCEEDED -> when (val total = outputIntOrNull(info.outputData, SteamAssetDownloadWorker.KEY_TOTAL)) {
                null -> SetupOperationState.RecoveryRequired(
                    "The artwork run finished, but its result can't be verified. " +
                        "Request it again from Settings.",
                )
                0 -> SetupOperationState.Succeeded(
                    "No artwork was available yet — it can be re-run after your library sync.",
                )
                else -> SetupOperationState.Succeeded(null)
            }
            WorkInfo.State.FAILED -> SetupOperationState.Failed(failureReason)
            WorkInfo.State.CANCELLED -> SetupOperationState.Cancelled
            else -> null
        }
    }

private fun outputIntOrNull(data: Data, key: String): Int? =
    data.keyValueMap[key] as? Int
