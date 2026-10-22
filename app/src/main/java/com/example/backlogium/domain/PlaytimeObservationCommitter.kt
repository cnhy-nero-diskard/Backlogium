package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HiddenGameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.LocalTimingEvidence
import com.example.backlogium.data.repo.CloudPendingEvidencePruner
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter.SessionKey
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitMapper
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitMapper.RecoveryCommitContext
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.domain.CloudPresencePlaytimePlacement.Input
import com.example.backlogium.gamification.RuleConfig
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one path from an observed playtime reading to stored sessions, recovery state, and daily
 * progress — whatever observed it.
 *
 * Both the periodic library poll and the play-triggered targeted fetch commit through here, which
 * is what makes exactly-once crediting structural rather than careful: every baseline this reads
 * is read inside the caller's transaction, so whichever observer commits second sees an already
 * advanced baseline, computes no delta, and records no session and no minutes. Neither observer
 * decides anything; the transaction does.
 *
 * Since the recovery work landed (recover-missed-playtime), the commit derives the authoritative
 * per-game delta with [SessionDiffer] (the sole quantity authority), then routes it through one
 * pure [PlaytimeAttributionPlanner] plan. Every authoritative range becomes one funded
 * contribution: ordinary short-gap sessions keep their timestamped row but carry a funded session
 * share linked to the actual session id; evidence-informed shares become bounded timed placements;
 * unmatched minutes become durable dated unknown-time allocations or pending date-unknown quantity.
 * All of it commits atomically through [PlaytimeRecoveryCommitAdapter] with the baseline, the daily
 * snapshot and the raw daily credit. A candidate synthetic session whose minutes become recovery
 * buckets is discarded, never written and then corrected.
 *
 * The recovery window's endpoint is the *reading* instant (`syncedAt`); a targeted fetch's supplied
 * play instant is separate evidence. Family-shared games never enter snapshot recovery, and with no
 * admitted account identity the commit only records Steam's own baselines — it authors no quantity.
 *
 * It derives nothing beyond sessions and raw credit from playtime — XP, levels, and streaks stay
 * with the single on-device author the caller invokes after the commit.
 */
@Singleton
class PlaytimeObservationCommitter @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val profileDao: PlayerProfileDao,
    private val hiddenGameDao: HiddenGameDao,
    private val differ: SessionDiffer,
    private val time: TimeProvider,
    private val sessionActionWriter: SessionActionWriter,
    private val recoveryAdapter: PlaytimeRecoveryCommitAdapter,
    private val pendingEvidencePruner: CloudPendingEvidencePruner? = null,
) {
    /**
     * One game's playtime as an observer saw it.
     *
     * [name] and [iconUrl] may be blank, and a blank one leaves the stored value alone: the
     * targeted fetch asks an endpoint that answers a playtime question and carries no icon, and
     * even the library poll occasionally returns an empty name. Blanking a stored name or icon
     * because *this* observation did not carry one would be a loss, not an update.
     */
    data class ObservedGame(
        val appId: Long,
        val name: String,
        val iconUrl: String,
        val playtimeForever: Int,
        val playtime2Weeks: Int,
    )

    /** What the commit did, for the caller's diagnostics and for tests. */
    data class Commit(
        val actions: List<SessionDiffer.SessionAction>,
        val playedDeltaByAppId: Map<Long, Int>,
        /** Boundaries clamped this commit for a backward clock movement (#115) — the caller's to record. */
        val clockRollbacks: List<SessionDiffer.ClockRollback> = emptyList(),
        /** Funded recovery contributions this commit created or replaced. */
        val recoveredContributions: Int = 0,
        /** One durable staged-finalization journal, when this raw operation included correction. */
        val finalizationOperationId: String? = null,
        /** Games whose first usable cumulative reading was only a baseline (no lifetime credit). */
        val baselineEstablishedAppIds: Set<Long> = emptySet(),
    ) {
        val recordedPlay: Boolean get() = playedDeltaByAppId.values.any { it > 0 }
    }

    /**
     * Validate placement evidence and hold the reader-promotion fence across the caller's Room
     * transaction. The transaction must start inside this block so the lock order stays reader
     * state -> Room, matching cloud ingestion and promotion.
     */
    suspend fun <T> withValidatedPlacement(
        placement: Input?,
        block: suspend (validatedPlacement: Input?, pruningIdentity: CloudReaderIdentity?) -> T,
    ): T {
        val pruner = pendingEvidencePruner
            ?: return block(placement, placement?.readerIdentity)
        return pruner.withValidatedEvidence(
            evidenceIdentity = placement?.readerIdentity,
        ) { validatedIdentity, pruningIdentity ->
            val validatedPlacement = placement?.takeIf {
                validatedIdentity != null && it.readerIdentity == validatedIdentity
            }
            block(validatedPlacement, pruningIdentity)
        }
    }

    /**
     * Validated cloud plus retained local timing candidates for late contribution reconciliation.
     * This is still placement evidence only; quantity remains Steam-authorized and this read has no
     * session/ledger write side effects.
     */
    suspend fun recoveryEvidenceForReconciliation(
        account: String,
        placement: Input?,
        activeReaderIdentity: CloudReaderIdentity?,
        observedAt: Long,
    ): List<PlaytimeTimingEvidence> {
        val cloud = placement?.intervals.orEmpty()
            .mapNotNull { it.toRecoveryEvidence(account, activeReaderIdentity, observedAt) }
        val local = recoveryAdapter.localTimingEvidenceForAccount(account)
            .mapNotNull { it.toRecoveryEvidence() }
        return (cloud + local).distinctBy { it.evidenceId }
    }

    /**
     * Must be called inside the caller's database transaction — the freshness of the baselines read
     * here is the whole double-count story, and it only holds if the read and the write share one
     * transaction.
     *
     * @param observedPlayAt when the play happened, which the caller supplies rather than this path
     *   reading a clock: the targeted fetch knows the session end it was triggered by, and every
     *   attempt of its schedule must report that same instant no matter which attempt observed the
     *   increase. It becomes each session's end.
     * @param syncedAt when the observation was made — the stored `lastSyncedAt`, and a genuine
     *   clock reading rather than an event time. It is the recovery window endpoint for every
     *   quantity/state decision; a supplied play instant is clipped inside `previous..syncedAt`.
     * @param expectedAccount the account this reading belongs to. When supplied, a mismatch with
     *   the durable profile's `steamId` rejects the commit outright — including a cleared profile
     *   after a reset, so a stale worker cannot repopulate state for an account that is gone.
     * @param knownPlayInstantAt a targeted fetch's supplied play instant, kept as evidence rather
     *   than overwritten by the later fetch time.
     */
    suspend fun commit(
        observed: List<ObservedGame>,
        observedPlayAt: Long,
        syncedAt: Long,
        placement: CloudPresencePlaytimePlacement.Input? = null,
        pruningIdentity: CloudReaderIdentity? = null,
        expectedAccount: String? = null,
        knownPlayInstantAt: Long? = null,
        ruleConfig: RuleConfig = RuleConfig(),
        ruleConfigVersion: Long = 0L,
        earnedAchievements: Set<RecoveryAchievementSnapshot> = emptySet(),
    ): Commit {
        val profile = profileDao.get()
        val durableAccount = profile?.steamId?.trim().orEmpty()
        val expected = expectedAccount?.trim()
        // Active-account fence: a named account must match the durable profile exactly, even when
        // the profile was cleared by a reset (durable blank) — the old worker must not write.
        if (!expected.isNullOrEmpty() && expected != durableAccount) {
            return Commit(actions = emptyList(), playedDeltaByAppId = emptyMap())
        }
        val account = if (!expected.isNullOrEmpty()) expected else durableAccount
        // Without an admitted account identity there is no boundary to hang recovery on: only
        // Steam's own baselines are recorded, never a session or a credit.
        val authoringEnabled = account.isNotEmpty()

        // A targeted fetch's supplied play instant (observedPlayAt) is evidence, never the fetch
        // time: when it differs from the observation clock it is this operation's known play
        // instant. The caller may pass an explicit one; this derivation covers the existing
        // targeted-worker contract without renaming the shared signature.
        val knownPlay = knownPlayInstantAt ?: observedPlayAt.takeIf { it != syncedAt }

        val lastSyncAt = profile?.lastSyncAt ?: 0L
        val polls = observed.map { SessionDiffer.PollGame(it.appId, it.playtimeForever) }

        // Family-shared games are excluded from the owned diffing scope; their stored identity must
        // be read explicitly so a borrowed game never enters snapshot recovery and never has its
        // owned metadata overwritten (only the ownership converter may change that).
        val sharedAppIds = gameDao.sharedGames().mapTo(mutableSetOf()) { it.appId }

        // Recovery baselines are read in bulk inside this transaction, before the diff: the
        // committed high-water is the authoritative per-game quantity anchor, so a restore that
        // moved it out of step with Game.lastPlaytime is reconciled here in the same atomic
        // boundary instead of letting the two disagree for a commit (design.md Decision 3).
        val recoveryStates = if (authoringEnabled) {
            recoveryAdapter.recoveryStates(account)
        } else {
            emptyMap()
        }
        val storedGames = gameDao.ownedGamesForDiffing().associateBy { it.appId }
        val existingGames = storedGames.mapValues { (appId, game) ->
            val state = recoveryStates[appId]
            if (state != null && state.highWaterMinutes != game.lastPlaytime.toLong()) {
                gameDao.updateSteamFields(
                    appId = appId,
                    name = game.name,
                    iconUrl = game.iconUrl,
                    playtimeForever = game.playtimeForever,
                    playtime2Weeks = game.playtime2Weeks,
                    lastPlaytime = state.highWaterMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    lastSyncedAt = game.lastSyncedAt,
                    lastPlayedAt = game.lastPlayedAt,
                    returnedToPlayAt = game.returnedToPlayAt,
                )
                game.copy(lastPlaytime = state.highWaterMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } else {
                game
            }
        }
        // Restore creates game skeletons without a live detector baseline. An account-wide sync
        // of other games does not make their zero defaults a trustworthy prior observation.
        val gamesWithBaseline = existingGames.filter { (appId, game) ->
            appId in recoveryStates || game.lastSyncedAt > 0L || game.lastPlaytime > 0
        }
        val openSessionsByAppId = sessionDao.getAllOpenSessions().associateBy { it.appId }
        // One bulk session scan for the whole commit: consumed-span reconstruction must never
        // re-read the session ledger per game.
        val sessionsById = sessionDao.getAll().associate { it.id to it }

        val diff = if (lastSyncAt == 0L) {
            // Nothing has ever been polled: record totals, synthesize no sessions. Only deltas
            // observed after the baseline become play.
            differ.baseline(polls)
        } else {
            differ.diff(
                polls = polls,
                priorStates = gamesWithBaseline.mapValues { (appId, game) ->
                    val open = openSessionsByAppId[appId]
                    SessionDiffer.GameDiffState(
                        lastPlaytime = game.lastPlaytime,
                        openSession = open?.let {
                            SessionDiffer.OpenSession(
                                startAt = it.startAt,
                                minutes = it.minutes,
                                lastIncreaseAt = it.endAt ?: it.startAt,
                            )
                        },
                    )
                },
                now = observedPlayAt,
                // A session cannot start after it ended. The two agree by construction for the
                // periodic poll, whose observation time is its own clock; a targeted fetch reports
                // an earlier session end, and this keeps a stored session's bounds sane if a poll
                // has landed in between.
                previousPollAt = lastSyncAt.coerceAtMost(observedPlayAt),
            )
        }

        // A game Steam reports for the first time is owned by definition (only the owned-games
        // poll admits new rows). Insert its row before any session write so a funded share can
        // never reference a missing game.
        observed.forEach { game ->
            if (game.appId in sharedAppIds || existingGames.containsKey(game.appId)) return@forEach
            gameDao.insertSteamGameIfMissing(
                appId = game.appId,
                name = game.name,
                iconUrl = game.iconUrl,
                playtimeForever = game.playtimeForever,
                playtime2Weeks = game.playtime2Weeks,
                lastPlaytime = diff.newLastPlaytime[game.appId] ?: game.playtimeForever,
                lastSyncedAt = syncedAt,
                firstSeenAt = null,
                lastPlayedAt = null,
            )
        }

        // In production the worker supplies placement only through withValidatedPlacement,
        // which holds the reader-promotion fence until this transaction completes. Keep the
        // identity check here too, so evidence cannot bypass validation against the active reader.
        val acceptedPlacement = if (pendingEvidencePruner == null) {
            placement
        } else {
            placement?.takeIf {
                it.readerIdentity != null && it.readerIdentity == pruningIdentity
            }
        }
        // The planner requires a fence for cloud evidence; without a pruner the caller-supplied
        // identity is the only identity ever observable, so it is the fence.
        val activeReaderIdentity = pruningIdentity ?: acceptedPlacement?.readerIdentity

        val zone = time.zone()
        val hiddenIds = hiddenGameDao.hiddenAppIds().toSet()
        val goalIds = existingGames.values.filter { it.isGoal }.mapTo(mutableSetOf()) { it.appId }

        // Cloud intervals and retained local timing facts both become candidate evidence for the
        // planner to validate, union, clip and bound — never a second quantity calculation, and no
        // extra Steam request.
        val cloudEvidenceByAppId = acceptedPlacement?.intervals.orEmpty()
            .mapNotNull { it.toRecoveryEvidence(account, activeReaderIdentity, syncedAt) }
            .groupBy { it.appId }
        val localEvidenceByAppId = if (authoringEnabled) {
            recoveryAdapter.localTimingEvidenceForAccount(account)
                .mapNotNull { it.toRecoveryEvidence() }
                .groupBy { it.appId }
        } else {
            emptyMap()
        }

        val appliedActions = mutableListOf<SessionDiffer.SessionAction>()
        val deferredActions = mutableListOf<SessionDiffer.SessionAction>()
        val allocationCredit = mutableMapOf<String, DailyProgressCredit>()
        val committedGraphs = mutableListOf<PlaytimeRecoveryCommitMapper.GraphOutcome>()
        val baselineEstablished = mutableSetOf<Long>()
        var recoveredContributions = 0
        // Load prior placements once for this admitted observation batch. The adapter chunks the
        // game IDs below SQLite's bind limit, and the result stays local to this raw transaction.
        val placementInputsByAppId = if (authoringEnabled) {
            recoveryAdapter.placementInputsForGames(
                account = account,
                appIds = observed.asSequence().filterNot { it.appId in sharedAppIds }
                    .map { it.appId }.toList(),
                sessionsById = sessionsById,
            )
        } else {
            emptyMap()
        }

        for (game in observed) {
            if (game.appId in sharedAppIds) continue
            if (!authoringEnabled) continue

            val existing = existingGames[game.appId]
            val delta = diff.playedDeltaByAppId[game.appId] ?: 0
            val placementInputs = placementInputsByAppId[game.appId]
            // A historical graph without its observation cannot define the new detector lineage.
            // Keep it in an older epoch even if this first live total is below its old endpoint.
            val baselineEpoch = recoveryStates[game.appId]?.baselineEpoch
                ?: Math.addExact(placementInputs?.maxBaselineEpoch ?: 0L, 1L)
            val state = if (existing != null && game.appId in gamesWithBaseline) {
                recoveryStates[game.appId] ?: recoveryAdapter.ensureLegacyBaseline(account, existing, baselineEpoch)
            } else {
                null
            }
            val previousObservationAt = state?.lastObservationAt
            val previousZone = state?.lastObservationZone
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            val priorOpen = openSessionsByAppId[game.appId]?.let {
                SessionDiffer.OpenSession(it.startAt, it.minutes, it.endAt ?: it.startAt)
            }
            val evidence = cloudEvidenceByAppId[game.appId].orEmpty() +
                localEvidenceByAppId[game.appId].orEmpty()
            val consumed = placementInputs?.consumedSpans.orEmpty()
            val pendingPlacements = placementInputs?.pendingPlacements.orEmpty()
            val context = RecoveryCommitContext(
                windowStartAt = previousObservationAt,
                windowEndAt = syncedAt,
                hiddenAtCredit = game.appId in hiddenIds,
                goalAtCredit = game.appId in goalIds,
                now = syncedAt,
            )

            // A backward clock movement invalidates this window even when the endpoint ordering
            // alone would not: the differ reported the clamp, so the plan stays conservative.
            val clockAnomalous = diff.clockRollbacks.any { it.appId == game.appId }

            // 1) Retry retained pending quantity against its original contribution bounds and
            //    frozen zone/eligibility. A later observation can never move already-reported
            //    quantity into a newer window; if the original bounds or zone are unavailable or
            //    invalid, leave it pending unchanged rather than borrowing today's context.
            var planned = false
            for (pending in pendingPlacements) {
                val pendingWindowStartAt = pending.windowStartAt ?: continue
                val pendingZone = pending.zone ?: continue
                if (pending.windowEndAt < pendingWindowStartAt) continue

                val pendingContext = context.copy(
                    windowStartAt = pending.windowStartAt,
                    windowEndAt = pending.windowEndAt,
                    hiddenAtCredit = pending.hiddenAtCredit,
                    goalAtCredit = pending.goalAtCredit,
                )
                val pendingPlan = PlaytimeAttributionPlanner.plan(
                    PlaytimeRecoveryRequest(
                        account = account,
                        appId = game.appId,
                        baselineEpoch = pending.contributionId.baselineEpoch,
                        authorizedDeltaMinutes = 0,
                        unplacedPendingMinutes = pending.minutes.toInt(),
                        previousObservationAt = pendingWindowStartAt,
                        recoveryObservedAt = pending.windowEndAt,
                        zone = pendingZone,
                        previousZone = pendingZone,
                        evaluationDate = Instant.ofEpochMilli(pending.windowEndAt)
                            .atZone(pendingZone).toLocalDate(),
                        priorHighWaterMinutes = null,
                        existingContributionId = pending.contributionId,
                        priorOpenSession = null,
                        clockAnomalous = false,
                    ),
                )
                if (applyPlanIfNeeded(
                        plan = pendingPlan,
                        context = pendingContext,
                        replaceExisting = true,
                        evidenceIdentity = null,
                        appliedActions = appliedActions,
                        allocationCredit = allocationCredit,
                        graphOutcomes = committedGraphs,
                    )
                ) {
                    recoveredContributions++
                    planned = true
                }
            }

            // 2) The fresh delta, authorized by the differ, as its own funded contribution.
            if (delta > 0) {
                val plan = PlaytimeAttributionPlanner.plan(
                    PlaytimeRecoveryRequest(
                        account = account,
                        appId = game.appId,
                        baselineEpoch = state?.baselineEpoch ?: 1L,
                        authorizedDeltaMinutes = delta,
                        unplacedPendingMinutes = 0,
                        previousObservationAt = previousObservationAt,
                        recoveryObservedAt = syncedAt,
                        zone = zone,
                        previousZone = previousZone,
                        evaluationDate = time.today(),
                        priorHighWaterMinutes = state?.highWaterMinutes?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                            ?: existing?.lastPlaytime,
                        priorOpenSession = priorOpen,
                        evidence = evidence,
                        consumedSpans = consumed,
                        activeReaderIdentity = activeReaderIdentity,
                        clockAnomalous = clockAnomalous,
                        knownPlayInstantAt = knownPlay,
                    ),
                )
                if (applyPlanIfNeeded(
                        plan = plan,
                        context = context,
                        replaceExisting = false,
                        evidenceIdentity = activeReaderIdentity
                            ?.let { "${it.account}:${it.readerGeneration}" },
                        appliedActions = appliedActions,
                        allocationCredit = allocationCredit,
                        graphOutcomes = committedGraphs,
                    )
                ) {
                    recoveredContributions++
                    planned = true
                }
            }

            if (!planned) {
                // A zero-delta reading (with nothing pending to place) keeps the differ's close.
                deferredActions += diff.actions.filter { it.appId == game.appId }
            }

            // Advance the recovery high-water/boundary and record the daily snapshot — for a
            // regression, a stale reading or a first baseline, inside the same transaction.
            val baselineResult = recoveryAdapter.advanceBaselineState(
                account = account,
                appId = game.appId,
                absoluteMinutes = game.playtimeForever.toLong(),
                observedAt = syncedAt,
                zone = zone,
                epoch = baselineEpoch,
            )
            if (baselineResult.firstBaseline) baselineEstablished += game.appId
        }

        val deferredEffective = sessionActionWriter.applySessionActions(deferredActions)
        appliedActions += deferredEffective

        attributeDailyProgress(appliedActions, goalIds, zone, hiddenIds).forEach { (date, credit) ->
            dailyProgressDao.ensureDate(date)
            dailyProgressDao.addMinutes(date, credit.minutesPlayed, credit.goalMinutesPlayed)
        }
        allocationCredit.forEach { (date, credit) ->
            dailyProgressDao.ensureDate(date)
            dailyProgressDao.addMinutes(date, credit.minutesPlayed, credit.goalMinutesPlayed)
        }

        // A single journal owns the exact corrective+earned input set for this raw transaction.
        // Its strict payload commits atomically with the baseline, contribution graphs and any
        // newly refreshed achievements below; process death cannot leave a bare RAW_COMMITTED row.
        val finalizationOperationId = recoveryAdapter.attachFinalizationPayload(
            outcomes = committedGraphs,
            account = account,
            evaluationDate = time.today(),
            config = ruleConfig,
            configVersion = ruleConfigVersion,
            freshAchievements = earnedAchievements,
            now = syncedAt,
        )

        observed.forEach { game ->
            if (game.appId in sharedAppIds) return@forEach
            val existing = existingGames[game.appId]
            val name = game.name.ifBlank { existing?.name.orEmpty() }
            val iconUrl = game.iconUrl.ifBlank { existing?.iconUrl.orEmpty() }
            val lastPlaytime = diff.newLastPlaytime[game.appId] ?: game.playtimeForever
            gameDao.insertSteamGameIfMissing(
                appId = game.appId,
                name = name,
                iconUrl = iconUrl,
                playtimeForever = game.playtimeForever,
                playtime2Weeks = game.playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = syncedAt,
                firstSeenAt = null,
                lastPlayedAt = null,
            )
            gameDao.updateSteamFields(
                appId = game.appId,
                name = name,
                iconUrl = iconUrl,
                playtimeForever = game.playtimeForever,
                playtime2Weeks = game.playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = syncedAt,
                // Targeted playtime has no Steam-owned last-played value; preserve the stored
                // recency facts until the periodic owned-games poll supplies one.
                lastPlayedAt = existing?.lastPlayedAt,
                returnedToPlayAt = existing?.returnedToPlayAt,
            )
            // Even a zero-delta successful baseline makes closed evidence ending by this
            // observation ineligible for future windows. The caller's Room transaction ties
            // retirement to the session and Steam baseline commit (including rollback).
            pendingEvidencePruner?.afterBaseline(pruningIdentity, game.appId, syncedAt)
        }

        return Commit(
            actions = appliedActions,
            playedDeltaByAppId = if (authoringEnabled) diff.playedDeltaByAppId else emptyMap(),
            clockRollbacks = diff.clockRollbacks,
            recoveredContributions = recoveredContributions,
            finalizationOperationId = finalizationOperationId,
            baselineEstablishedAppIds = baselineEstablished,
        )
    }

    /**
     * Route one funded plan into storage: apply its session actions, resolve the actual session ids
     * for its funded shares, then commit the contribution graph. Every authoritative range becomes
     * one contribution whose placements conserve it exactly — an ordinary session is funded by a
     * session share carrying its `addedMinutes` (never the whole mixed row), so the row's unlinked
     * legacy remainder is preserved and the minutes are credited once.
     *
     * @return true when the plan produced any persisted change.
     */
    private suspend fun applyPlanIfNeeded(
        plan: PlaytimeAttributionPlan,
        context: RecoveryCommitContext,
        replaceExisting: Boolean,
        evidenceIdentity: String?,
        appliedActions: MutableList<SessionDiffer.SessionAction>,
        allocationCredit: MutableMap<String, DailyProgressCredit>,
        graphOutcomes: MutableList<PlaytimeRecoveryCommitMapper.GraphOutcome>,
    ): Boolean {
        if (!recoveryAdapter.wouldCreate(plan, replaceExisting)) return false

        val planKeys = PlaytimeRecoveryCommitMapper.timedSessionKeys(plan)
        val actions = plan.sessionActions + PlaytimeRecoveryCommitMapper.timedSessionActions(plan)
        val effective = if (actions.isEmpty()) {
            emptyList()
        } else {
            sessionActionWriter.applySessionActions(actions, timingInformedSessionKeys = planKeys)
        }
        appliedActions += effective
        // Resolve the ids of exactly this plan's funded rows, after writing them, so a session
        // share links to the actual persisted session (a wider/merged row still links by key).
        val sessionIds = resolveSessionIds(plan, effective)
        val outcome = recoveryAdapter.commitPlanGraph(
            plan, context, replaceExisting, evidenceIdentity, sessionIds, effective,
        )
        if (outcome.committed) graphOutcomes += outcome
        outcome.allocationCreditByDate.forEach { (date, credit) ->
            val prior = allocationCredit[date]
            allocationCredit[date] = if (prior == null) {
                credit
            } else {
                DailyProgressCredit(
                    minutesPlayed = prior.minutesPlayed + credit.minutesPlayed,
                    goalMinutesPlayed = prior.goalMinutesPlayed + credit.goalMinutesPlayed,
                )
            }
        }
        return true
    }

    private suspend fun resolveSessionIds(
        plan: PlaytimeAttributionPlan,
        effective: List<SessionDiffer.SessionAction>,
    ): Map<SessionKey, Long> {
        val ids = mutableMapOf<SessionKey, Long>()
        for (action in effective) {
            if (action.addedMinutes <= 0) continue
            val endAt = action.storedEndAt
            sessionDao.findByNaturalKey(plan.appId, action.startAt, endAt)?.let { row ->
                ids[SessionKey(plan.appId, action.startAt, endAt)] = row.id
            }
        }
        return ids
    }

    /** The end boundary a session action persists (all three action kinds carry one). */
    private val SessionDiffer.SessionAction.storedEndAt: Long
        get() = when (this) {
            is SessionDiffer.SessionAction.Open -> endAt
            is SessionDiffer.SessionAction.Extend -> endAt
            is SessionDiffer.SessionAction.Close -> endAt
        }

    /**
     * Convert one validated cloud interval into candidate timing evidence for the planner. The
     * planner re-validates the identity fence, the account, the gap tolerance and the window clip,
     * so nothing here is authority — it only refuses to *claim* timing the interval itself cannot
     * confirm (UNKNOWN/LEGACY coverage, absent observed-until, one-sided lapses).
     */
    private fun CloudPresenceInterval.toRecoveryEvidence(
        account: String,
        activeIdentity: CloudReaderIdentity?,
        windowEnd: Long,
    ): PlaytimeTimingEvidence? {
        if (appId <= 0L) return null
        if (coverage == CloudCoverageState.UNKNOWN || coverage == CloudCoverageState.LEGACY_TRANSITIONS) {
            return null
        }
        val rawEnd = endAt ?: windowEnd.takeIf { ongoing } ?: return null
        val confirmedEnd = when (coverage) {
            CloudCoverageState.CONTINUOUS -> rawEnd
            CloudCoverageState.OBSERVED_UNTIL -> observedUntil ?: return null
            CloudCoverageState.UNKNOWN, CloudCoverageState.LEGACY_TRANSITIONS -> return null
        }.coerceAtMost(rawEnd)
        if ((coverageLapseFrom == null) != (coverageLapseRecoveredAt == null)) return null
        if (confirmedEnd <= startAt) return null
        return PlaytimeTimingEvidence(
            evidenceId = "cloud:$appId:$startAt:$confirmedEnd:${coverageLapseFrom ?: 0}:${coverageLapseRecoveredAt ?: 0}",
            appId = appId,
            account = account,
            readerIdentity = activeIdentity,
            source = PlaytimeEvidenceSource.CLOUD,
            startAt = startAt,
            endAt = confirmedEnd,
            gapFromAt = coverageLapseFrom,
            gapRecoveredAt = coverageLapseRecoveredAt,
        )
    }

    /**
     * Convert one retained local timing fact (task 4.3 output) into candidate evidence. Only the
     * validated prefix `firstSampleAt..observedUntil` is claimed; a coverage loss is mapped only
     * when it overlaps that prefix (clipped to it), so a loss outside the proven prefix never
     * rejects the valid portion. A first sample or an end alone is not an interval.
     */
    private fun LocalTimingEvidence.toRecoveryEvidence(): PlaytimeTimingEvidence? {
        val start = firstSampleAt ?: return null
        val end = observedUntil ?: return null
        if (end <= start) return null
        val lossFrom = continuityLossFrom
        val lossTo = continuityLossRecoveredAt
        val gap = if (lossFrom != null && lossTo != null && lossTo > start && lossFrom < end) {
            maxOf(lossFrom, start) to minOf(lossTo, end)
        } else {
            null
        }
        val gapFrom = gap?.takeIf { it.second > it.first }?.first
        val gapTo = gap?.takeIf { it.second > it.first }?.second
        return PlaytimeTimingEvidence(
            evidenceId = "local|$account|$generation|$appId|$start|$end",
            appId = appId,
            account = account,
            source = PlaytimeEvidenceSource.LOCAL,
            startAt = start,
            endAt = end,
            gapFromAt = gapFrom,
            gapRecoveredAt = gapTo,
        )
    }
}
