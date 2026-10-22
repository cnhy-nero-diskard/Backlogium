package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.dao.BaselineAdvance
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.LocalTimingEvidence
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.RecoveryClockStates
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitMapper.GraphOutcome
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitMapper.RecoveryCommitContext
import com.example.backlogium.domain.ConsumedPlaytimeSpan
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.PlaytimeAttributionPlan
import com.example.backlogium.domain.PlaytimeContributionId
import com.example.backlogium.domain.PlaytimePlacementProvenance
import com.example.backlogium.domain.PlaytimeQuantityRange
import com.example.backlogium.domain.RecoveryContributionSnapshot
import com.example.backlogium.domain.RecoveryDatedCreditSnapshot
import com.example.backlogium.domain.RecoveryDerivedFinalizeInputs
import com.example.backlogium.domain.RecoveryEarnedInputs
import com.example.backlogium.domain.RecoveryFinalizeKind
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.gamification.RuleConfig
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The persistence half of the recovery commit path (recover-missed-playtime, tasks 4.1/4.2/4.4/
 * 4.5 and 6.1). Every function here is called inside the caller's Room transaction, so the reads
 * that authorize a plan and the writes that persist it share one atomic boundary: whichever
 * observer commits second reads an already-advanced baseline, and a failed persist rolls back the
 * baseline, the contribution graph, the sessions and the raw daily credit together.
 *
 * Every authoritative range becomes one funded contribution. The DAO's
 * [PlaytimeRecoveryDao.commitAllocatedGraph] enforces the conservation invariant
 * `authorized = active timed shares + active unknown-time shares + pending shares`, rank-exact
 * recipe materialization and per-session funded-share bounds, so an out-of-order or conflicting
 * replay cannot add credit. This adapter only decides *what* to persist and when a replay or a
 * still-invalid pending replan is a no-op.
 */
@Singleton
class PlaytimeRecoveryCommitAdapter @Inject constructor(
    private val recoveryDao: PlaytimeRecoveryDao,
    private val sessionDao: SessionDao,
) {

    /** Retained pending date-unknown quantity belonging to one stored contribution. */
    data class PendingPlacement(
        val minutes: Long,
        val contributionId: PlaytimeContributionId,
        val windowStartAt: Long?,
        val windowEndAt: Long,
        val zone: ZoneId?,
        val hiddenAtCredit: Boolean,
        val goalAtCredit: Boolean,
    )

    /** Transaction-local placement inputs grouped by game for one observation batch. */
    data class GamePlacementInputs(
        val consumedSpans: List<ConsumedPlaytimeSpan> = emptyList(),
        val pendingPlacements: List<PendingPlacement> = emptyList(),
        val maxBaselineEpoch: Long = 0L,
    )

    /** A session natural key maps to its persisted row id for funded-share linking. */
    data class SessionKey(val appId: Long, val startAt: Long, val endAt: Long?)

    /** Bulk per-game recovery baselines for one account, read once per commit. */
    suspend fun recoveryStates(account: String): Map<Long, GameRecoveryState> =
        recoveryDao.statesForAccount(account).associateBy { it.appId }

    /** Bulk retained local timing facts for one account, read once per commit (task 4.3 output). */
    suspend fun localTimingEvidenceForAccount(account: String): List<LocalTimingEvidence> =
        recoveryDao.evidenceForAccount(account)

    /**
     * Seed the per-game recovery baseline from the existing diff baseline (design.md Decision 3:
     * "Reuse `Game.lastPlaytime` as the existing detector baseline during transition and keep it
     * transactionally consistent with the new recovery state"). A game that was quiet while this
     * change shipped has a trustworthy high-water total and a known *per-game* last sync instant
     * but no captured historical zone — the seed records the total and the instant and leaves the
     * zone unknown rather than inventing one.
     *
     * A missing per-game sync instant is kept honestly null: the profile's global last sync is a
     * different, account-wide boundary and must not be invented as this game's observation anchor.
     * An unanchored game therefore plans as pending (`NO_BASELINE_WINDOW`) until a valid new
     * observation window exists.
     */
    suspend fun ensureLegacyBaseline(account: String, game: Game, epoch: Long = 1L): GameRecoveryState? {
        if (game.source != GameSource.STEAM_OWNED) return null
        val existing = recoveryDao.state(account, game.appId)
        if (existing != null) return existing
        val previousAt = game.lastSyncedAt.takeIf { it > 0L } ?: 0L
        val seeded = GameRecoveryState(
            account = account,
            appId = game.appId,
            baselineEpoch = epoch,
            highWaterMinutes = game.lastPlaytime.toLong(),
            lastObservationAt = previousAt.takeIf { it > 0L },
            lastObservationDate = null,
            lastObservationZone = null,
            clockState = RecoveryClockStates.UNKNOWN,
            updatedAt = previousAt,
        )
        // Fenced at the storage layer against a profile that no longer owns this account.
        recoveryDao.upsertStateIfAccountActive(seeded)
        return seeded
    }

    /**
     * Load pending quantity and already-funded evidence spans for the observed games in bounded
     * account/game batches. These rows are read inside the caller's raw Room transaction and never
     * cached beyond it. This replaces per-contribution placement queries while retaining one
     * pending record per contribution identity.
     */
    suspend fun placementInputsForGames(
        account: String,
        appIds: List<Long>,
        sessionsById: Map<Long, Session>,
    ): Map<Long, GamePlacementInputs> {
        val requestedApps = appIds.distinct()
        if (requestedApps.isEmpty()) return emptyMap()

        val consumedByApp = mutableMapOf<Long, MutableList<ConsumedPlaytimeSpan>>()
        val pendingByApp = mutableMapOf<Long, MutableList<PendingPlacement>>()
        val maxEpochByApp = mutableMapOf<Long, Long>()
        requestedApps.chunked(SQLITE_BIND_BATCH_SIZE).forEach { appBatch ->
            val contributions = recoveryDao.contributionsForGames(account, appBatch)
            val timedByContribution = recoveryDao.activeTimedPlacementsForGames(account, appBatch)
                .groupBy { it.contributionId }
            val allocationsByContribution = recoveryDao.activeAllocationsForGames(account, appBatch)
                .groupBy { it.contributionId }

            contributions.forEach { contribution ->
                maxEpochByApp[contribution.appId] = maxOf(
                    maxEpochByApp[contribution.appId] ?: 0L, contribution.baselineEpoch,
                )
                timedByContribution[contribution.contributionId].orEmpty().forEach { placement ->
                    val span = PlaytimeRecoveryCommitMapper.consumedSpanFrom(placement)
                    if (span != null) {
                        consumedByApp.getOrPut(contribution.appId, ::mutableListOf)
                            .add(ConsumedPlaytimeSpan(span.first, span.second))
                    } else {
                        // Older rows may lack structured evidence metadata. Preserve their prior
                        // fallback to the positive host-session interval, if it is still present.
                        val session = placement.sessionId?.let(sessionsById::get)
                        val end = session?.endAt ?: session?.startAt
                        if (session != null && end != null && end > session.startAt) {
                            consumedByApp.getOrPut(contribution.appId, ::mutableListOf)
                                .add(ConsumedPlaytimeSpan(session.startAt, end))
                        }
                    }
                }

                // A game may retain pending minutes from multiple independent windows. Keep one
                // placement per contribution and preserve the credit flags from its first active
                // pending row, as the former per-contribution query did.
                val pending = allocationsByContribution[contribution.contributionId].orEmpty()
                    .filter { it.pendingDate }
                val minutes = pending.sumOf { it.minutes }
                if (minutes > 0L) {
                    val creditFlags = pending.first()
                    pendingByApp.getOrPut(contribution.appId, ::mutableListOf).add(
                        PendingPlacement(
                            minutes = minutes,
                            contributionId = PlaytimeContributionId(
                                account = contribution.account,
                                appId = contribution.appId,
                                baselineEpoch = contribution.baselineEpoch,
                                quantityRange = PlaytimeQuantityRange(
                                    contribution.fromMinutes.toInt(),
                                    contribution.toMinutes.toInt(),
                                ),
                            ),
                            windowStartAt = contribution.windowStartAt,
                            windowEndAt = contribution.windowEndAt,
                            zone = contribution.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                                ?: creditFlags.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
                            hiddenAtCredit = creditFlags.hiddenAtCredit,
                            goalAtCredit = creditFlags.goalAtCredit,
                        ),
                    )
                }
            }
        }

        return requestedApps.associateWith { appId ->
            GamePlacementInputs(
                consumedSpans = consumedByApp[appId].orEmpty(),
                pendingPlacements = pendingByApp[appId].orEmpty(),
                maxBaselineEpoch = maxEpochByApp[appId] ?: 0L,
            )
        }
    }

    /**
     * Whether committing [plan] creates new rows, i.e. is not a no-op. A fresh plan is a replay
     * when its identity already exists; a replacement is a no-op when the plan is *still* pending
     * and the retained pending quantity is unchanged — an invalid window has not improved, so the
     * revision must not churn and the retained window/quantity must stay put.
     */
    suspend fun wouldCreate(plan: PlaytimeAttributionPlan, replaceExisting: Boolean): Boolean {
        val storageId = PlaytimeRecoveryCommitMapper.storageContributionId(plan) ?: return false
        val existing = recoveryDao.contribution(storageId) ?: return true
        if (!replaceExisting) return false
        if (plan.pending != null) {
            val activePending = recoveryDao.activeAllocations(storageId)
                .filter { it.pendingDate }
                .sumOf { it.minutes }
            return activePending != plan.pending.minutes.toLong()
        }
        return true
    }

    /**
     * Persist one plan's contribution graph, journal, funded timed/session shares and unknown-time
     * allocations atomically. Sessions are expected to be applied via [SessionActionWriter] first;
     * [sessionIds] (resolved for exactly this plan's rows, after writing them) links each funded
     * share to its actual session row.
     *
     * idempotency: an identical replayed plan collides on deterministic placement ids and changes
     * nothing ([GraphOutcome.committed] is false); a conflicting replay of the same identity
     * (different quantity or window) is rejected by the DAO and rolls the transaction back.
     */
    suspend fun commitPlanGraph(
        plan: PlaytimeAttributionPlan,
        context: RecoveryCommitContext,
        replaceExisting: Boolean,
        evidenceIdentity: String?,
        sessionIds: Map<SessionKey, Long>,
        appliedSessionActions: List<SessionDiffer.SessionAction>,
    ): GraphOutcome {
        val storageId = PlaytimeRecoveryCommitMapper.storageContributionId(plan)
            ?: return GraphOutcome()
        val existing = recoveryDao.contribution(storageId)
        val revision = when {
            existing == null -> 1L
            replaceExisting -> Math.addExact(existing.revision, 1L)
            else -> existing.revision
        }
        val contribution = PlaytimeRecoveryCommitMapper.contributionRow(plan, context, existing, revision)
            ?: return GraphOutcome()
        val committed = existing == null || replaceExisting
        val operationId = PlaytimeRecoveryIdentity.journalOperationId(
            storageId, revision, evidenceIdentity,
        )
        // A compact recipe is storage/read bookkeeping, not itself corrective provenance: an
        // eligible current-date single-day share remains an ordinary earned SYNC even if its
        // materialization is deferred. Only a moved/recovery-history graph needs staged correction.
        val needsDerivedWork = replaceExisting ||
            plan.provenance == PlaytimePlacementProvenance.RETROACTIVE_PLAY

        // Capture the superseded rows before marking them, so the journal can name them.
        val previousTimedPlacements = if (replaceExisting && existing != null) {
            recoveryDao.activeTimedPlacements(storageId)
        } else {
            emptyList()
        }
        val previousAllocations = if (replaceExisting && existing != null) {
            recoveryDao.activeAllocations(storageId)
        } else {
            emptyList()
        }
        val replacedPlacementIds = (previousTimedPlacements.map { it.placementId } +
            previousAllocations.map { it.placementId }).distinct()
        val retainedHiddenAtCredit = previousAllocations.firstOrNull()?.hiddenAtCredit
            ?: previousTimedPlacements.firstOrNull()?.hiddenAtCredit
        val retainedGoalAtCredit = previousAllocations.firstOrNull()?.goalAtCredit
            ?: previousTimedPlacements.firstOrNull()?.goalAtCredit
        val placementContext = if (replaceExisting && existing != null) {
            context.copy(
                windowStartAt = existing.windowStartAt,
                windowEndAt = existing.windowEndAt,
                hiddenAtCredit = retainedHiddenAtCredit ?: context.hiddenAtCredit,
                goalAtCredit = retainedGoalAtCredit ?: context.goalAtCredit,
            )
        } else {
            context
        }
        if (replaceExisting && existing != null) {
            recoveryDao.supersedeTimedPlacementsUpTo(storageId, revision)
            recoveryDao.supersedeAllocationsUpTo(storageId, revision)
            recoveryDao.advanceRevision(storageId, revision)
        }

        val sessionLookup: (startAt: Long, endAt: Long) -> Long? = { startAt, endAt ->
            sessionIds[SessionKey(plan.appId, startAt, endAt)]
        }
        val timed = PlaytimeRecoveryCommitMapper.evidenceTimedRows(
            plan, contribution, revision, placementContext, sessionLookup,
        ) + PlaytimeRecoveryCommitMapper.sessionShareRows(
            plan, contribution, revision, placementContext, sessionLookup,
        )
        val (allocations, recipe) = PlaytimeRecoveryCommitMapper.allocationsAndRecipe(
            plan, contribution, revision, placementContext,
        )
        // Structural validation, rank-exact recipe materialization, session bounds, graph
        // conservation and overlap rejection all happen here.
        recoveryDao.commitAllocatedGraph(contribution, timed, allocations, recipe)

        if (committed) {
            val changedDates = PlaytimeRecoveryCommitMapper.journalAffectedDates(plan)
            recoveryDao.upsertJournal(
                PlaytimeRecoveryJournal(
                    operationId = operationId,
                    contributionId = storageId,
                    account = plan.account,
                    appId = plan.appId,
                    priorRevision = existing?.revision,
                    currentRevision = revision,
                    affectedDatesCsv = changedDates.joinToString(","),
                    replacedPlacementIdsCsv = replacedPlacementIds.takeIf { it.isNotEmpty() }
                        ?.joinToString(","),
                    replacedRecipeJson = null,
                    derivedStatus = if (needsDerivedWork) {
                        RecoveryJournalStatus.RAW_COMMITTED
                    } else {
                        RecoveryJournalStatus.FINALIZED
                    },
                    // The committer attaches the strict structured-JSON finalization payload to
                    // corrective journals before its surrounding raw Room transaction commits.
                    payloadJson = null,
                    createdAt = context.now,
                    updatedAt = context.now,
                ),
            )
        }

        return GraphOutcome(
            committed = committed,
            timedSessionKeys = PlaytimeRecoveryCommitMapper.timedSessionKeys(plan),
            allocationCreditByDate = PlaytimeRecoveryCommitMapper.allocationDailyCredit(
                plan, placementContext.hiddenAtCredit, placementContext.goalAtCredit,
            ),
            earnedCreditByDate = if (
                committed && !needsDerivedWork && plan.provenance == PlaytimePlacementProvenance.SYNC
            ) {
                PlaytimeRecoveryCommitMapper.datedCreditByDate(
                    plan,
                    placementContext.hiddenAtCredit,
                    placementContext.goalAtCredit,
                    appliedSessionActions,
                )
            } else {
                emptyMap()
            },
            changedDates = PlaytimeRecoveryCommitMapper.journalAffectedDates(plan),
            operationId = operationId.takeIf { committed },
            contributionSnapshot = RecoveryContributionSnapshot(
                account = contribution.account,
                appId = contribution.appId,
                baselineEpoch = contribution.baselineEpoch,
                fromMinutes = contribution.fromMinutes,
                toMinutes = contribution.toMinutes,
                revision = contribution.revision,
                authorizedMinutes = contribution.authorizedMinutes,
            ).takeIf { committed },
            requiresFinalization = needsDerivedWork && committed,
            earnedContribution = plan.provenance == PlaytimePlacementProvenance.SYNC &&
                !needsDerivedWork && committed,
        )
    }

    /**
     * Attach one durable staged operation to the correction journals produced by this raw commit.
     * One primary journal carries the complete exact corrective/earned input set; any additional
     * correction journals are marked finalized so startup resumes the shared operation only once.
     */
    suspend fun attachFinalizationPayload(
        outcomes: List<GraphOutcome>,
        account: String,
        evaluationDate: LocalDate,
        config: RuleConfig,
        configVersion: Long,
        freshAchievements: Set<com.example.backlogium.domain.RecoveryAchievementSnapshot>,
        now: Long,
    ): String? {
        val correctionOutcomes = outcomes.filter { it.requiresFinalization && it.operationId != null }
        if (correctionOutcomes.isEmpty()) return null
        val earnedOutcomes = outcomes.filter { it.earnedContribution }
        val earned = RecoveryEarnedInputs(
            contributions = earnedOutcomes.mapNotNull { it.contributionSnapshot }.toSet(),
            achievements = freshAchievements,
            datedCredits = earnedOutcomes.flatMap { outcome ->
                val contribution = outcome.contributionSnapshot ?: return@flatMap emptyList()
                outcome.earnedCreditByDate.map { (date, credit) ->
                    RecoveryDatedCreditSnapshot(
                        account = contribution.account,
                        appId = contribution.appId,
                        contributionId = contribution.identity(),
                        revision = contribution.revision,
                        date = date,
                        minutes = credit.minutesPlayed,
                        goalMinutes = credit.goalMinutesPlayed,
                    )
                }
            }.toSet(),
        )
        val corrective = correctionOutcomes.mapNotNull { it.contributionSnapshot }.toSet()
        val kind = if (earned.isEmpty) RecoveryFinalizeKind.CORRECTIVE_ONLY else RecoveryFinalizeKind.MIXED
        val primaryId = requireNotNull(correctionOutcomes.first().operationId)
        val inputs = RecoveryDerivedFinalizeInputs(
            kind = kind,
            operationId = primaryId,
            account = account,
            evaluationDate = evaluationDate,
            configVersion = configVersion,
            config = config,
            correctiveContributions = corrective,
            earned = earned,
        )
        val primary = recoveryDao.journal(primaryId)
            ?: error("correction journal $primaryId disappeared before finalization payload commit")
        recoveryDao.upsertJournal(
            primary.copy(payloadJson = RecoveryDerivedFinalizeInputs.encode(inputs), updatedAt = now),
        )
        correctionOutcomes.drop(1).mapNotNull { it.operationId }.forEach { operationId ->
            recoveryDao.markJournalStatus(operationId, RecoveryJournalStatus.FINALIZED, now)
        }
        return primaryId
    }

    /** Record the observation: advance the high-water/boundary or log a regression snapshot. */
    suspend fun advanceBaselineState(
        account: String,
        appId: Long,
        absoluteMinutes: Long,
        observedAt: Long,
        zone: ZoneId,
        epoch: Long = 1L,
    ): BaselineAdvance {
        val date = if (observedAt > 0L) {
            Instant.ofEpochMilli(observedAt).atZone(zone).toLocalDate().toString()
        } else {
            null
        }
        return recoveryDao.advanceBaseline(
            account = account,
            appId = appId,
            absoluteMinutes = absoluteMinutes,
            observedAt = observedAt,
            zone = if (date != null) zone.toString() else null,
            date = date,
            epoch = epoch,
        )
    }

    private companion object {
        // Leave headroom below SQLite's 999-variable limit for other bound parameters.
        const val SQLITE_BIND_BATCH_SIZE = 900
    }
}
