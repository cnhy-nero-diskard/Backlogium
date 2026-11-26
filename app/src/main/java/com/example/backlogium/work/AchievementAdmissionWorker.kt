package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.achievement.AchievementRecomputeRecovery
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.AchievementAdmissionDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.AchievementWatchWork
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.SingleGameRefresh
import com.example.backlogium.domain.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.UUID
import timber.log.Timber

/** Bounded FIFO drain for committed owned and automatic family-shared admissions. */
@HiltWorker
class AchievementAdmissionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val credentials: CredentialsProvider,
    private val accountChangeMarker: AccountChangeMarkerStore,
    private val database: BacklogiumDatabase,
    private val admissionDao: AchievementAdmissionDao,
    private val gameDao: GameDao,
    private val profileDao: PlayerProfileDao,
    private val hiddenGamesRepository: HiddenGamesRepository,
    private val achievementRepository: AchievementRepository,
    private val recomputeRecovery: AchievementRecomputeRecovery,
    private val scheduler: AchievementAdmissionWorkScheduler,
    private val syncCoordinator: SteamSyncCoordinator,
    private val time: TimeProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val steamId = inputData.getString(KEY_STEAM_ID)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val generation = inputData.getLong(KEY_GENERATION, -1L)
        return try {
            syncCoordinator.withLock { recomputeRecovery.resumeWhileLocked() }
            val creds = credentials.currentCredentials() as? CredentialsState.Configured ?: return Result.success()
            if (creds.steamId != steamId || !isActive(steamId, generation)) return Result.success()
            val batch = admissionDao.eligible(steamId, generation, time.nowMillis(), PER_RUN_CAP)
            for (candidate in batch) {
                if (!isActive(steamId, generation)) return Result.success()
                if (gameDao.getById(candidate.appId) == null || hiddenGamesRepository.isHidden(candidate.appId)) {
                    admissionDao.delete(steamId, generation, candidate.appId)
                    continue
                }
                if (!candidate.cataloguePending && !candidate.personalStatePending && !candidate.globalPercentagesPending) {
                    admissionDao.delete(steamId, generation, candidate.appId)
                    continue
                }
                // A reserved attempt is consumed even if cancellation or process death interrupts it.
                val dispatchAt = time.nowMillis()
                val recoveryAt = dispatchAt + retryDelayMillis(candidate.attempts + 1)
                if (admissionDao.beginAttempt(
                        steamId, generation, candidate.appId,
                        expectedAttempts = candidate.attempts,
                        maxAttempts = MAX_ATTEMPTS,
                        now = dispatchAt,
                        recoveryAt = recoveryAt,
                    ) == 0
                ) {
                    admissionDao.deleteExhausted(steamId, generation, candidate.appId, MAX_ATTEMPTS, dispatchAt)
                    continue
                }
                val attempt = admissionDao.get(steamId, generation, candidate.appId)?.attempts ?: continue
                achievementRepository.refreshAdmission(
                    apiKey = creds.apiKey,
                    steamId = steamId,
                    appId = candidate.appId,
                    admittedAt = candidate.admittedAt,
                    cataloguePending = candidate.cataloguePending,
                    personalStatePending = candidate.personalStatePending,
                    globalPercentagesPending = candidate.globalPercentagesPending,
                    commit = { merge, outcome ->
                        var recomputeNeeded = false
                        syncCoordinator.withLock {
                            val committed = accountChangeMarker.withAccountState {
                                if (!isActive(steamId, generation) ||
                                    admissionDao.get(steamId, generation, candidate.appId) == null ||
                                    gameDao.getById(candidate.appId) == null ||
                                    hiddenGamesRepository.isHidden(candidate.appId)
                                ) {
                                    database.withTransaction {
                                        admissionDao.delete(steamId, generation, candidate.appId)
                                    }
                                    SingleGameRefresh.Discarded
                                } else {
                                    database.withTransaction {
                                        val beforeXpInputs = database.achievementDao().getForGame(candidate.appId)
                                            .filter { it.unlocked && !it.retired }
                                            .associate { it.apiName to it.snapshotPercent }
                                        merge().also { result ->
                                            val afterXpRows = database.achievementDao().getForGame(candidate.appId)
                                                .filter { it.unlocked && !it.retired }
                                            val afterXpInputs = afterXpRows.associate { it.apiName to it.snapshotPercent }
                                            recomputeNeeded = beforeXpInputs != afterXpInputs
                                            if (recomputeNeeded) {
                                                database.achievementWatchDao().insert(AchievementWatchWork(
                                                    eventId = UUID.randomUUID().toString(),
                                                    account = steamId,
                                                    appId = candidate.appId,
                                                    epoch = "admission-hydration",
                                                    observedAt = time.nowMillis(),
                                                    recomputeNeeded = true,
                                                    epochClosed = true,
                                                    recomputeSource = "XP_INTEGRITY_CORRECTION",
                                                ))
                                            }
                                            val metadata = database.gameAchievementSyncDao().get(candidate.appId)
                                            val catalogueFetchedAt = database.achievementCatalogueDao().cache(candidate.appId)?.fetchedAt
                                            val catalogueAvailable = catalogueFetchedAt?.let {
                                                time.nowMillis() - it <= AchievementRepository.SCHEMA_WINDOW_MILLIS
                                            } == true
                                            val personalStateAvailable =
                                                (metadata?.playerStateFetchedAt ?: Long.MIN_VALUE) >= candidate.admittedAt
                                            val globalPercentagesAvailable =
                                                (metadata?.globalPercentagesFetchedAt ?: Long.MIN_VALUE) >= candidate.admittedAt
                                            val cataloguePending = candidate.cataloguePending && !catalogueAvailable &&
                                                (outcome.cataloguePending || outcome.catalogueObserved)
                                            val personalStatePending = candidate.personalStatePending && !personalStateAvailable &&
                                                (outcome.personalStatePending || outcome.personalStateObserved)
                                            // Earlier global freshness does not resolve unlocks still missing snapshots after a retry.
                                            val globalPercentagesPending =
                                                (candidate.globalPercentagesPending && !globalPercentagesAvailable &&
                                                    (outcome.globalPercentagesPending || outcome.globalPercentagesObserved)) ||
                                                (afterXpRows.any { it.snapshotPercent == null } && outcome.retryableFailure &&
                                                    !outcome.globalPercentagesObserved)
                                            val retryNeeded = outcome.retryableFailure ||
                                                (cataloguePending && outcome.catalogueObserved) ||
                                                (personalStatePending && outcome.personalStateObserved) ||
                                                (globalPercentagesPending && outcome.globalPercentagesObserved)
                                            when {
                                                result is SingleGameRefresh.Discarded ->
                                                    admissionDao.delete(steamId, generation, candidate.appId)

                                                !cataloguePending && !personalStatePending && !globalPercentagesPending ->
                                                    admissionDao.delete(steamId, generation, candidate.appId)

                                                retryNeeded && attempt < MAX_ATTEMPTS ->
                                                    admissionDao.recordOutcome(
                                                        steamId = steamId,
                                                        generation = generation,
                                                        appId = candidate.appId,
                                                        cataloguePending = cataloguePending,
                                                        personalStatePending = personalStatePending,
                                                        globalPercentagesPending = globalPercentagesPending,
                                                        nextEligibleAt = maxOf(
                                                            time.nowMillis() + retryDelayMillis(attempt),
                                                            outcome.retryAt ?: 0L,
                                                        ),
                                                    )

                                                else -> admissionDao.delete(steamId, generation, candidate.appId)
                                            }
                                        }
                                    }
                                }
                            }
                            if (recomputeNeeded) recomputeRecovery.resumeWhileLocked()
                            committed
                        }
                    },
                )
                if (!isActive(steamId, generation)) return Result.success()
            }

            val remaining = admissionDao.state(steamId, generation)
            if (remaining.pendingCount > 0) {
                val delay = ((remaining.nextEligibleAt ?: time.nowMillis()) - time.nowMillis()).coerceAtLeast(0L)
                scheduler.enqueue(steamId, generation, delay)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Admission achievement drain will retry")
            Result.retry()
        }
    }

    private suspend fun isActive(steamId: String, generation: Long): Boolean =
        generation >= 0 && accountChangeMarker.pendingSteamId() == null &&
            accountChangeMarker.generation() == generation &&
            credentials.currentCredentials()?.steamId == steamId && profileDao.get()?.steamId == steamId

    companion object {
        const val KEY_STEAM_ID = "steam_id"
        const val KEY_GENERATION = "account_generation"
        const val PER_RUN_CAP = 8
        const val MAX_ATTEMPTS = 5
        private const val TAG = "AchievementAdmission"

        internal fun retryDelayMillis(attempt: Int): Long =
            (60_000L * (1L shl (attempt - 1).coerceIn(0, 5))).coerceAtMost(60 * 60_000L)
    }
}
