package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.RecoveryReadStatusStore
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.PlaytimeObservation
import com.example.backlogium.data.repo.RecentPlaytimeRepository
import com.example.backlogium.data.repo.CloudPresencePlacementReader
import com.example.backlogium.data.repo.readOrNull
import com.example.backlogium.data.repo.CloudReadTrigger
import com.example.backlogium.domain.CloudPresencePlaytimePlacement
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.SyncDerivedStateWriter
import com.example.backlogium.domain.RecoveryDerivedFinalizer
import com.example.backlogium.domain.RecoveryPlacementReconcileUseCase
import com.example.backlogium.domain.UnknownTimeMaterializer
import com.example.backlogium.domain.VersionedRuleConfig
import com.example.backlogium.domain.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One attempt of the play-triggered targeted playtime fetch: ask Steam about the single game whose
 * session just ended, and commit the increase if it has appeared yet.
 *
 * Steam does not publish a game's updated `playtime_forever` when the game exits — it settles over
 * the following minutes — so an attempt that observes nothing is the expected case rather than a
 * failure. Each attempt enqueues only its successor ([PostPlaySyncScheduler]), so the schedule
 * terminates by *not* acting: the attempt that sees the increase enqueues nothing, and nothing has
 * to be cancelled.
 *
 * The worker owns no derivation. It hands its observation to
 * [PlaytimeObservationCommitter] — the same path the periodic poll commits through, whose
 * in-transaction baseline read is what makes double-counting impossible — and then triggers the
 * existing derived-value write. It never returns `Result.retry()`: an exhausted or superseded
 * schedule is concluded, and WorkManager backing off to re-run it would be re-running a decision
 * that has already been made.
 */
@HiltWorker
class PostPlaySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recentPlaytime: RecentPlaytimeRepository,
    private val database: BacklogiumDatabase,
    private val gameDao: GameDao,
    private val profileDao: PlayerProfileDao,
    private val committer: PlaytimeObservationCommitter,
    private val derivedStateWriter: SyncDerivedStateWriter,
    private val generations: PostPlayGenerationCoordinator,
    private val scheduler: PostPlaySyncScheduler,
    private val diagnostics: SyncRunRecorder,
    private val time: TimeProvider,
    private val syncCoordinator: SteamSyncCoordinator,
    private val credentials: CredentialsProvider,
    private val accountChangeMarker: AccountChangeMarkerStore,
    private val cloudPresencePlacementReader: CloudPresencePlacementReader,
    private val unknownTimeMaterializer: UnknownTimeMaterializer,
    private val recoveryDerivedFinalizer: RecoveryDerivedFinalizer,
    private val recoveryPlacementReconciler: RecoveryPlacementReconcileUseCase,
    private val recoveryReadStatus: RecoveryReadStatusStore,
) : CoroutineWorker(appContext, params) {

    /** What one attempt's request turned out to be, before it is compared to the baseline. */
    private sealed interface Attempt {
        /** Steam answered about one or more recent games. The requested game is selected below. */
        data class Observed(val observations: List<PlaytimeObservation>) : Attempt

        /** A successful request with nothing in it: Steam unconfigured, or an empty response. */
        data object Empty : Attempt

        /** The request failed. Recorded as a failure, but the schedule carries on regardless. */
        data class Failed(val message: String) : Attempt
    }

    override suspend fun doWork(): Result {
        val appId = inputData.getLong(KEY_APP_ID, 0L)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val sessionEndAt = inputData.getLong(KEY_SESSION_END_AT, 0L)
        val generation = inputData.getLong(KEY_GENERATION, 0L)
        val steamId = inputData.getString(KEY_STEAM_ID).orEmpty()
        // Nothing to scope the work to. Only reachable if input data were lost, which WorkManager
        // does not do; recorded nowhere, because there is no game to record it against.
        if (appId <= 0L || sessionEndAt <= 0L || steamId.isBlank()) return Result.success()

        val scope = diagnostics.begin(trigger(appId, attempt), attempt = runAttemptCount)
        var outcome = SyncOutcome.SUCCESS
        var error: String? = null
        var examined = 0
        var updated = 0
        return try {
            // Cheap early exit before spending a request. Not the guard — the generation can be
            // superseded while the fetch below is in flight — just a way to not pay for an attempt
            // whose schedule is already known to be over.
            if (!generations.isActive(appId, generation)) {
                outcome = SyncOutcome.SKIPPED_SUPERSEDED
                return Result.success()
            }

            // The baseline session synthesis will compare against, read before the fetch: an
            // "increase" means an increase over *that* value rather than over the raw stored
            // playtime, or the two could disagree about whether there is anything to record.
            val baseline = gameDao.getById(appId)?.lastPlaytime
            val previousPollAt = profileDao.get()?.lastSyncAt ?: 0L

            val result = syncCoordinator.withLock {
                // Opportunistic: this only avoids two concurrent Steam conversations in one
                // process. Waiting is fine, failing or skipping is not — correctness lives in the
                // commit transaction, never in this mutex. The marker and identity checks are the
                // durable account-change barrier: a running request may finish, but it cannot
                // start or commit against the account that replaced its schedule.
                if (!isAccountActive(steamId)) {
                    outcome = SyncOutcome.SKIPPED_SUPERSEDED
                    return@withLock null
                }
                val resumeAt = time.nowMillis()
                unknownTimeMaterializer.materializeAll(steamId, resumeAt)
                recoveryDerivedFinalizer.resumePending(steamId, resumeAt)
                fetch(scope)
            } ?: return Result.success()

            val observation = when (result) {
                is Attempt.Failed -> {
                    outcome = SyncOutcome.FAILED
                    error = result.message
                    syncCoordinator.withLock {
                        if (isAccountActive(steamId)) {
                            recoveryReadStatus.unavailable(steamId, time.nowMillis())
                        }
                    }
                    null
                }

                Attempt.Empty -> null

                is Attempt.Observed -> {
                    examined = result.observations.size
                    result.observations.firstOrNull { it.appId == appId } ?: run {
                        // Attributing another game's minutes to this one would be worse than the
                        // staleness this fetch exists to fix. Discard, and let the attempt count
                        // as unproductive.
                        outcome = SyncOutcome.SKIPPED_UNEXPECTED_GAME
                        error = "expected app " + appId +
                            ", Steam answered about " + result.observations.first().appId
                        null
                    }
                }
            }

            if (observation != null && baseline != null) {
                runCatching { recoveryReadStatus.observedPlay(steamId, appId, sessionEndAt, force = true) }
            }

            // A trustworthy first/unchanged read still goes through the committer: it establishes
            // this game's durable observation boundary and can place retained pending quantity.
            val increased = observation != null &&
                baseline != null &&
                observation.playtimeForever > baseline

            if (observation != null) {
                val configAtCompute = derivedStateWriter.configuration()
                val placementSnapshot = if (increased &&
                    CloudPresencePlaytimePlacement.shouldConsult(previousPollAt, sessionEndAt)
                ) {
                    cloudPresencePlacementReader.readOrNull(CloudReadTrigger.POST_PLAY, previousPollAt, sessionEndAt)
                } else {
                    null
                }
                val recorded = commitIfStillActive(
                    appId = appId,
                    generation = generation,
                    sessionEndAt = sessionEndAt,
                    steamId = steamId,
                    observed = observation,
                    baselineBeforeFetch = baseline,
                    scope = scope,
                    placement = placementSnapshot?.let {
                        CloudPresencePlaytimePlacement.Input(it.intervals, it.readerIdentity)
                    },
                    configAtCompute = configAtCompute,
                )
                if (recorded == null) {
                    // Refused: a newer session end took this game over while the fetch was in
                    // flight, and a superseded attempt commits nothing.
                    outcome = SyncOutcome.SKIPPED_SUPERSEDED
                    return Result.success()
                }
                if (recorded.recordedPlay || recorded.alreadyCommittedIncrease) {
                    if (recorded.recordedPlay) updated = 1
                    outcome = SyncOutcome.SUCCESS
                    return Result.success()
                }
                // A successful unchanged (or first-baseline) targeted read still advances this
                // game's durable observation boundary and can place prior pending date-unknown
                // credit. It is unproductive only for this schedule attempt, so later configured
                // attempts may observe Steam's delayed publication.
                outcome = SyncOutcome.SUCCESS
            }

            // Nothing to record. Continue the schedule, or let it end: an exhausted schedule is an
            // ordinary outcome, with the periodic poll remaining the backstop for whenever Steam
            // publishes the increase.
            if (!PostPlaySyncScheduler.isLastAttempt(attempt)) {
                val enqueued = syncCoordinator.withLock {
                    if (!isAccountActive(steamId)) {
                        null
                    } else {
                        generations.ifActive(appId, generation) {
                            scheduler.enqueueSuccessor(
                                appId = appId,
                                attempt = attempt,
                                sessionEndAt = sessionEndAt,
                                generation = generation,
                                steamId = steamId,
                            )
                            true
                        }
                    }
                }
                // A superseded attempt must not append into the schedule that replaced it.
                if (enqueued == null) outcome = SyncOutcome.SKIPPED_SUPERSEDED
            }
            Result.success()
        } catch (e: CancellationException) {
            outcome = SyncOutcome.INCOMPLETE
            throw e
        } catch (e: Exception) {
            // An unexpected failure (a database write, say) ends this schedule quietly rather than
            // asking WorkManager to retry it: the periodic poll will observe the same increase, and
            // a backed-off re-run of one attempt would arrive after the schedule was over anyway.
            outcome = SyncOutcome.FAILED
            error = e.message ?: "Post-play attempt failed"
            Result.success()
        } finally {
            withContext(NonCancellable) {
                runCatching { diagnostics.finish(scope, outcome, error, examined, updated) }
            }
        }
    }

    /**
     * A failed request is an attempt that observed nothing, not a reason to end the schedule: the
     * next attempt is a minute or two away and Steam may well have published by then.
     */
    private suspend fun fetch(scope: SyncRunRecorder.RunScope): Attempt = try {
        recentPlaytime.recentlyPlayed(scope).let { observations ->
            if (observations.isEmpty()) Attempt.Empty else Attempt.Observed(observations)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Attempt.Failed(error.message ?: "Post-play fetch failed")
    }

    /**
     * Commit through the ordinary poll path, but only while this schedule is still the live one.
     *
     * The generation re-check and the commit share one critical section, so a session end that
     * supersedes this attempt linearizes either before the check — and nothing is written — or
     * after the commit, in which case the commit belonged to the then-active schedule.
     *
     * @return null when the attempt has been superseded and nothing was written; otherwise
     *   whether the commit actually recorded play, which it does not when another observer
     *   committed the same increase first.
     */
    private suspend fun commitIfStillActive(
        appId: Long,
        generation: Long,
        sessionEndAt: Long,
        steamId: String,
        observed: PlaytimeObservation,
        baselineBeforeFetch: Int?,
        scope: SyncRunRecorder.RunScope,
        placement: CloudPresencePlaytimePlacement.Input?,
        configAtCompute: VersionedRuleConfig,
    ): PostPlayCommitResult? {
        val rawCommit = syncCoordinator.withLock {
            if (!isAccountActive(steamId)) {
                null
            } else {
                generations.ifActive(appId, generation) {
                    withContext(NonCancellable) {
                        committer.withValidatedPlacement(placement) { validatedPlacement, pruningIdentity ->
                            val observedAt = time.nowMillis()
                            val lateEvidence = committer.recoveryEvidenceForReconciliation(
                                account = steamId,
                                placement = validatedPlacement,
                                activeReaderIdentity = pruningIdentity,
                                observedAt = observedAt,
                            )
                            if (lateEvidence.isNotEmpty()) {
                                recoveryPlacementReconciler.reconcileDelayedEvidenceWhileLocked(
                                    account = steamId,
                                    appId = appId,
                                    evidence = lateEvidence,
                                    activeReaderIdentity = pruningIdentity,
                                )
                            }
                             database.withTransaction {
                                // Targeted work is admitted only for the active credential pair.
                                // Persist that identity before account-scoped recovery writes so a
                                // blank-profile fresh install can establish its first baseline;
                                // a nonblank foreign profile is never overwritten.
                                var admittedProfile = profileDao.get()
                                if (admittedProfile == null) {
                                    profileDao.insertIfMissing()
                                    admittedProfile = profileDao.get()
                                }
                                val existingAccount = admittedProfile?.steamId?.trim().orEmpty()
                                if (existingAccount.isNotEmpty() && existingAccount != steamId) {
                                    return@withTransaction null
                                }
                                if (existingAccount.isEmpty()) {
                                    val profile = admittedProfile ?: com.example.backlogium.data.local.entity.PlayerProfile()
                                    profileDao.updateSteamIdentity(
                                        steamId = steamId,
                                        steamLevel = profile.steamLevel,
                                        personaName = profile.personaName,
                                        avatarUrl = profile.avatarUrl,
                                        storeRegion = profile.storeRegion,
                                    )
                                }
                                val commit = committer.commit(
                                    observed = listOf(
                                        PlaytimeObservationCommitter.ObservedGame(
                                            appId = observed.appId,
                                            name = observed.name,
                                            // This endpoint carries no icon, and blank leaves the stored one
                                            // alone. Steam owns the field; the next periodic poll sets it.
                                            iconUrl = "",
                                            playtimeForever = observed.playtimeForever,
                                            playtime2Weeks = observed.playtime2Weeks
                                                ?: gameDao.getById(observed.appId)?.playtime2Weeks
                                                ?: 0,
                                        ),
                                    ),
                                    // The session end this schedule was triggered by, not this attempt's own
                                    // clock: attempt four runs eight minutes after the play, and recording it
                                    // eight minutes late would make one game's history disagree with itself
                                    // depending on which attempt happened to see the increase.
                                    observedPlayAt = sessionEndAt,
                                    syncedAt = observedAt,
                                    placement = validatedPlacement,
                                    pruningIdentity = pruningIdentity,
                                    expectedAccount = steamId,
                                    knownPlayInstantAt = sessionEndAt,
                                    ruleConfig = configAtCompute.config,
                                    ruleConfigVersion = configAtCompute.version,
                                )
                                commit.clockRollbacks.forEach { scope.recordClockRollback() }
                                PostPlayCommitResult(
                                    creditedIncreaseAppIds = commit.playedDeltaByAppId
                                        .filterValues { it > 0 }.keys,
                                    finalizationOperationId = commit.finalizationOperationId,
                                    alreadyCommittedIncrease = baselineBeforeFetch != null &&
                                        observed.playtimeForever > baselineBeforeFetch &&
                                        !commit.recordedPlay,
                                    baselineEstablishedAppIds = commit.baselineEstablishedAppIds,
                                )
                            }
                        }
                    }
                }
            }
        } ?: return null

        // Finish durable recipe and journaled derived stages before another sync can re-base this
        // operation. The account lock remains the reset barrier; generation need not remain live
        // once its raw commit has succeeded.
        syncCoordinator.withLock {
            if (isAccountActive(steamId)) {
                val now = time.nowMillis()
                runCatching {
                    recoveryReadStatus.successfulRead(
                        account = steamId,
                        observedAt = now,
                        creditedIncreaseAppIds = rawCommit.creditedIncreaseAppIds,
                        baselineEstablishedAppIds = rawCommit.baselineEstablishedAppIds,
                    )
                }
                if (rawCommit.recordedPlay || rawCommit.finalizationOperationId != null) {
                    unknownTimeMaterializer.materializeAll(steamId, now)
                    recoveryDerivedFinalizer.resumePending(steamId, now)
                    if (rawCommit.recordedPlay && rawCommit.finalizationOperationId == null) {
                        derivedStateWriter.persist(time.today(), configAtCompute)
                    }
                }
            }
        }
        return rawCommit
    }

    private data class PostPlayCommitResult(
        val creditedIncreaseAppIds: Set<Long>,
        val finalizationOperationId: String?,
        val alreadyCommittedIncrease: Boolean,
        val baselineEstablishedAppIds: Set<Long>,
    ) {
        val recordedPlay: Boolean get() = creditedIncreaseAppIds.isNotEmpty()
    }

    /** Must be called while holding [syncCoordinator], which is the account-change barrier lock. */
    private suspend fun isAccountActive(expectedSteamId: String): Boolean {
        if (accountChangeMarker.pendingSteamId() != null) return false
        if (credentials.currentCredentials()?.steamId != expectedSteamId) return false
        val storedSteamId = profileDao.get()?.steamId?.trim()
        return storedSteamId.isNullOrBlank() || storedSteamId == expectedSteamId
    }

    companion object {
        const val KEY_APP_ID = "app_id"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_SESSION_END_AT = "session_end_at"
        const val KEY_GENERATION = "generation"
        const val KEY_STEAM_ID = "steam_id"

        /**
         * The sync-run trigger for one post-play attempt: play-triggered, scoped to a game, and
         * numbered so an exhausted schedule reads as four attempts rather than four identical rows.
         */
        fun trigger(appId: Long, attempt: Int): String = "post_play:$appId#${attempt + 1}"
    }
}
