package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudPresenceSessionIngestor
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CloudRoutineAttempt
import com.example.backlogium.data.repo.CloudCatchUpResult
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.domain.CloudPresencePlaytimePlacement
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.RecoveryPlacementReconcileUseCase
import com.example.backlogium.domain.RecoveryDerivedFinalizer
import com.example.backlogium.domain.UnknownTimeMaterializer
import kotlinx.coroutines.flow.first
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class CloudRoutineCatchUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reader: CloudPresenceRepository,
    private val ingestor: CloudPresenceSessionIngestor,
    private val settings: SettingsRepository,
    private val credentials: CredentialsProvider,
    private val gameDao: GameDao,
    private val committer: PlaytimeObservationCommitter,
    private val recoveryPlacementReconciler: RecoveryPlacementReconcileUseCase,
    private val syncCoordinator: SteamSyncCoordinator,
    private val accountChangeMarker: AccountChangeMarkerStore,
    private val unknownTimeMaterializer: UnknownTimeMaterializer,
    private val recoveryDerivedFinalizer: RecoveryDerivedFinalizer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = inputData.getString(KEY_ACCOUNT)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val generation = inputData.getLong(KEY_GENERATION, -1L)
        if (generation < 0L) return Result.success()
        var admittedAt: Long? = null
        val acquiredSnapshots = mutableListOf<com.example.backlogium.data.repo.CloudPresenceSnapshot>()
        return try {
            val attempt = reader.runRoutineCatchUp(
                account = account,
                generation = generation,
                consume = { snapshot ->
                    acquiredSnapshots += snapshot
                    ingestor.ingest(snapshot)
                },
                onAdmitted = { admittedAt = it },
            )
            if (attempt is CloudRoutineAttempt.Admitted &&
                settings.cloudReaderGeneration.first() == generation
            ) {
                val summary = when (val outcome = attempt.outcome) {
                    is CloudCatchUpResult.Complete -> if (outcome.noNewData) {
                        CloudReadSummaryOutcome.NO_NEW_DATA
                    } else CloudReadSummaryOutcome.COMPLETE
                    is CloudCatchUpResult.Partial -> CloudReadSummaryOutcome.PARTIAL
                    is CloudCatchUpResult.Failed -> CloudReadSummaryOutcome.FAILED
                    CloudCatchUpResult.Unavailable -> null
                }
                if (summary != null) settings.recordCloudRoutineOutcome(attempt.at, summary)
                syncCoordinator.withLock {
                    if (accountChangeMarker.pendingSteamId() != null ||
                        credentials.currentCredentials()?.steamId != account ||
                        settings.cloudReaderGeneration.first() != generation
                    ) return@withLock
                    // A compact recipe's unmaterialized logical tail cannot be atomically
                    // re-placed with new evidence. Drain bounded prior slices first, matching the
                    // periodic and targeted sync paths; the durable cursor makes this restart-safe.
                    unknownTimeMaterializer.materializeAll(account, attempt.at)
                    recoveryDerivedFinalizer.resumePending(account, attempt.at)
                    val ownedAppIds = gameDao.ownedGamesForDiffing().mapTo(mutableSetOf()) { it.appId }
                    acquiredSnapshots.forEach { snapshot ->
                        val identity = snapshot.readerIdentity ?: return@forEach
                        if (identity.account != account || identity.readerGeneration != generation) return@forEach
                        committer.withValidatedPlacement(
                            CloudPresencePlaytimePlacement.Input(snapshot.intervals, identity),
                        ) { validated, activeIdentity ->
                            if (validated == null || activeIdentity != identity) return@withValidatedPlacement
                            val evidence = committer.recoveryEvidenceForReconciliation(
                                account = account,
                                placement = validated,
                                activeReaderIdentity = activeIdentity,
                                observedAt = snapshot.windowEnd,
                            )
                            evidence.asSequence().map { it.appId }.filter { it in ownedAppIds }.distinct().forEach { appId ->
                                recoveryPlacementReconciler.reconcileDelayedEvidenceWhileLocked(
                                    account = account,
                                    appId = appId,
                                    evidence = evidence,
                                    activeReaderIdentity = activeIdentity,
                                )
                            }
                        }
                    }
                }
            }
            // A failed attempt has already consumed its shared cooldown. A future opportunity
            // resumes from its durable page position; WorkManager retries must not burst reads.
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Routine cloud catch-up failed")
            if (admittedAt != null && settings.cloudReaderGeneration.first() == generation) {
                settings.recordCloudRoutineOutcome(admittedAt, CloudReadSummaryOutcome.FAILED)
            }
            Result.success()
        }
    }

    companion object {
        const val KEY_ACCOUNT = "cloud_routine_account"
        const val KEY_GENERATION = "cloud_routine_generation"
        const val ONE_TIME_NAME = "cloud-routine-once"
        const val PERIODIC_NAME = "cloud-routine-periodic"
    }
}
