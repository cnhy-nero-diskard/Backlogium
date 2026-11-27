package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationProgress
import com.example.backlogium.domain.OperationStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class ReconciliationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val credentials: CredentialsProvider,
    private val database: BacklogiumDatabase,
    private val achievementRepository: AchievementRepository,
    private val diagnostics: SyncRunRecorder,
    private val syncCoordinator: SteamSyncCoordinator,
    private val accountChangeMarker: AccountChangeMarkerStore,
    private val operationReports: OperationReportStore = OperationReportStore(appContext),
) : CoroutineWorker(appContext, params) {

    private var generation: Long? = null
    private var executionId: String? = null
    private var executionStartedAt: Long? = null
    private var admittedSteamId: String? = null

    override suspend fun doWork(): Result {
        val inputGeneration = inputData.getLong(OP_ACCOUNT, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }
        val taggedGenerations = tags.filter { it.startsWith("setup-account:") }
            .map { it.substringAfter(":").toLongOrNull() ?: return deniedAdmission(inputGeneration) }
            .distinct()
        if (taggedGenerations.size > 1) return deniedAdmission(inputGeneration)
        val tagGeneration = taggedGenerations.singleOrNull()
        val admitted = captureReconciliationAdmission(
            syncCoordinator, accountChangeMarker, credentials, inputGeneration, tagGeneration,
        ) ?: return deniedAdmission(inputGeneration ?: tagGeneration)
        generation = admitted.generation
        admittedSteamId = admitted.credentials?.steamId

        val start = syncCoordinator.withLock {
            accountChangeMarker.withAccountState {
                if (!isAccountActive(admitted.generation, admitted.credentials?.steamId)) return@withAccountState null
                val report = operationReports.begin(
                    OperationChannel.ACHIEVEMENTS, id.toString(), admitted.generation,
                    retry = runAttemptCount > 0,
                )
                report to diagnostics.begin(
                    trigger = if (inputData.getBoolean(KEY_FORCE, false)) "reconciliation:forced" else "reconciliation:scheduled",
                    attempt = runAttemptCount,
                )
            }
        } ?: return deniedAdmission(inputGeneration ?: tagGeneration)
        executionId = start.first.progress.id
        executionStartedAt = start.first.startedAt
        val scope = start.second
        val creds = admitted.credentials
        if (creds == null) {
            val data = operationData(
                id.toString(), admitted.generation, OperationPhase.PREPARING, failed = true,
                executionId = executionId, startedAt = executionStartedAt,
            )
            saveReport(OperationStatus.FAILED, OperationPhase.PREPARING, null, 0)
            return Result.success(data)
        }

        val steamId = creds.steamId
        val force = inputData.getBoolean(KEY_FORCE, false)
        var counts: OperationCounts? = null
        var updated = 0
        var refreshedSoFar = 0
        var totalSoFar = 0
        var outcome = SyncOutcome.SUCCESS
        var errorMessage: String? = null
        val reporter = OperationReporter()

        return try {
            setProgress(androidx.work.Data.Builder()
                .putAll(operationData(
                    id.toString(), admitted.generation, OperationPhase.PREPARING,
                    executionId = executionId, startedAt = executionStartedAt,
                ))
                .putBoolean(KEY_RUNNING, true).build())
            val fetched = achievementRepository.fetchReconciliationGames(
                apiKey = creds.apiKey,
                steamId = steamId,
                scope = scope,
                onRefresh = { refresh ->
                    withContext(NonCancellable) {
                        syncCoordinator.withLock {
                            accountChangeMarker.withAccountState {
                                if (!isAccountActive(admitted.generation, steamId)) {
                                    throw CancellationException("Account changed during achievement refresh")
                                }
                                if (database.withTransaction {
                                    achievementRepository.applyRefreshCountingChanges(refresh)
                                }) updated++
                            }
                        }
                    }
                },
                onProgress = { refreshed, total ->
                    refreshedSoFar = refreshed
                    totalSoFar = total
                },
                onAttempts = { current ->
                    counts = current
                    reporter.report(OperationPhase.ACHIEVEMENTS, current) {
                        setProgress(androidx.work.Data.Builder()
                            .putAll(operationData(
                                id.toString(), admitted.generation, OperationPhase.ACHIEVEMENTS,
                                current, updated, executionId = executionId, startedAt = executionStartedAt,
                            ))
                            .putBoolean(KEY_RUNNING, true).build())
                    }
                },
            )
            refreshedSoFar = fetched.refreshed
            totalSoFar = fetched.total
            val finalCounts = counts ?: OperationCounts(fetched.total, fetched.total)
            val partial = finalCounts.failed > 0 && (fetched.refreshed > 0 || fetched.partialComponents)
            val status = when {
                finalCounts.failed == 0 -> OperationStatus.SUCCEEDED
                partial -> OperationStatus.PARTIAL
                else -> OperationStatus.FAILED
            }
            if (fetched.total - fetched.refreshed > 0) {
                Timber.tag(TAG).w("Reconciliation stopped early: ${fetched.refreshed}/${fetched.total} games refreshed")
            } else {
                Timber.tag(TAG).i("Reconciliation complete: ${fetched.refreshed}/${fetched.total} games refreshed")
            }
            saveReport(status, OperationPhase.ACHIEVEMENTS, finalCounts, updated)
            Result.success(androidx.work.Data.Builder()
                .putAll(operationData(
                    id.toString(), admitted.generation, OperationPhase.ACHIEVEMENTS,
                    finalCounts, updated, executionId = executionId, startedAt = executionStartedAt,
                    partial = partial,
                ))
                .putInt(KEY_REFRESHED, fetched.refreshed).putInt(KEY_TOTAL, fetched.total).build())
        } catch (e: CancellationException) {
            outcome = SyncOutcome.INCOMPLETE
            Timber.tag(TAG).i("Reconciliation cancelled early: $refreshedSoFar/$totalSoFar games refreshed")
            throw e
        } catch (e: Exception) {
            outcome = SyncOutcome.FAILED
            errorMessage = e.message ?: "Reconciliation failed"
            Timber.tag(TAG).e(e, "Reconciliation failed")
            saveReport(OperationStatus.RETRYING, OperationPhase.ACHIEVEMENTS, counts, updated)
            Result.retry()
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    syncCoordinator.withLock {
                        accountChangeMarker.withAccountState {
                            if (isAccountActive(admitted.generation, steamId)) {
                                diagnostics.finish(
                                    scope,
                                    outcome,
                                    errorMessage,
                                    gamesExamined = totalSoFar,
                                    gamesUpdated = refreshedSoFar,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun deniedAdmission(expectedGeneration: Long?): Result = Result.success(
        operationData(
            id.toString(), expectedGeneration ?: -1L, OperationPhase.PREPARING,
            failed = true, executionId = "denied:${id}", startedAt = System.currentTimeMillis(),
        ),
    )

    private suspend fun isAccountActive(expectedGeneration: Long, expectedSteamId: String?): Boolean =
        accountChangeMarker.pendingSteamId() == null &&
            accountChangeMarker.generation() == expectedGeneration &&
            credentials.currentCredentials()?.steamId == expectedSteamId

    private suspend fun saveReport(
        status: OperationStatus,
        phase: OperationPhase,
        counts: OperationCounts?,
        updated: Int,
    ) {
        val reportGeneration = generation ?: return
        val reportId = executionId ?: return
        val startedAt = executionStartedAt ?: return
        syncCoordinator.withLock {
            accountChangeMarker.withAccountState {
                if (isAccountActive(reportGeneration, admittedSteamId)) {
                    operationReports.save(
                        OperationChannel.ACHIEVEMENTS,
                        StoredOperationReport(
                            id.toString(), reportGeneration, startedAt,
                            OperationProgress(reportId, status, phase, counts, updated),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val PERIODIC_NAME = "steam_achievement_reconciliation"
        const val ONE_TIME_NAME = "steam_achievement_reconciliation_once"
        const val KEY_FORCE = "force"
        const val KEY_RUNNING = "running"
        const val KEY_REFRESHED = "refreshed"
        const val KEY_TOTAL = "total"
        private const val TAG = "Reconciliation"
    }
}

internal data class ReconciliationAdmission(
    val generation: Long,
    val credentials: CredentialsState.Configured?,
)

internal suspend fun captureReconciliationAdmission(
    coordinator: SteamSyncCoordinator,
    marker: AccountChangeMarkerStore,
    credentials: CredentialsProvider,
    inputGeneration: Long?,
    tagGeneration: Long?,
): ReconciliationAdmission? {
    if (inputGeneration != null && tagGeneration != null && inputGeneration != tagGeneration) return null
    val expectedGeneration = inputGeneration ?: tagGeneration
    return coordinator.withLock {
        marker.withAccountState {
            if (marker.pendingSteamId() != null) return@withAccountState null
            val generation = marker.generation()
            if (expectedGeneration != null && expectedGeneration != generation) return@withAccountState null
            ReconciliationAdmission(generation, credentials.currentCredentials())
        }
    }
}
