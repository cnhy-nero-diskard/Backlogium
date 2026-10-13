package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** One date's recomputed totals, as the backfill would write them. */
data class DailyProgressCorrection(
    val date: String,
    val storedMinutes: Int,
    val correctedMinutes: Int,
    val correctedGoalMinutes: Int,
)

/**
 * The whole decision, as a pure function: which dates disagree with canonical dated activity
 * (timestamped sessions, frozen funded timed shares, and materialized unknown-time allocations),
 * and what they should hold instead. Returns only the dates that need rewriting.
 *
 * Kept separate from [DailyProgressBackfillUseCase] so the rule can be exercised without a
 * DataStore, a recompute, or the one-shot guard that makes applying it unrepeatable.
 *
 * [stored] may contain dates the ledger cannot speak to; those before the earliest canonical
 * activity are excluded rather than zeroed.
 */
internal fun dailyProgressCorrections(
    sessions: List<Session>,
    goalAppIds: Set<Long>,
    stored: List<DailyProgress>,
    zone: ZoneId,
    timedShares: List<TimedContributionPlacement> = emptyList(),
    allocations: List<UnknownTimeAllocation> = emptyList(),
): List<DailyProgressCorrection> {
    data class Totals(var minutes: Long = 0L, var goals: Long = 0L)
    val totalsByDate = mutableMapOf<String, Totals>()
    fun add(date: String, minutes: Long, goal: Boolean) {
        if (minutes <= 0L) return
        val day = totalsByDate.getOrPut(date, ::Totals)
        day.minutes = Math.addExact(day.minutes, minutes)
        if (goal) day.goals = Math.addExact(day.goals, minutes)
    }

    val sessionsById = sessions.associateBy { it.id }
    val fundedBySession = mutableMapOf<Long, Long>()
    timedShares.filterNot { it.superseded }.forEach { share ->
        val sessionId = share.sessionId
        if (sessionId != null) {
            val host = sessionsById[sessionId]
            if (host != null) {
                require(host.appId == share.appId) {
                    "funded timed share ${share.placementId} has a different host game"
                }
                fundedBySession[sessionId] = Math.addExact(fundedBySession[sessionId] ?: 0L, share.minutes)
            }
        }
    }
    sessions.forEach { session ->
        val funded = fundedBySession[session.id] ?: 0L
        require(funded <= session.minutes.toLong()) {
            "funded shares $funded exceed session ${session.id} minutes ${session.minutes}"
        }
        val date = Instant.ofEpochMilli(session.startAt).atZone(zone).toLocalDate().toString()
        add(date, session.minutes.toLong() - funded, session.appId in goalAppIds)
    }
    // Every timed placement uses its own frozen date, even when it is hosted by a session whose
    // unlinked legacy remainder belongs to that session's start date. Missing/tombstoned hosts are
    // treated as detached for this daily projection rather than erasing their funded share.
    timedShares.filterNot { it.superseded || it.hiddenAtCredit }.forEach { share ->
        add(share.creditedDate, share.minutes, share.goalAtCredit)
    }
    allocations.filterNot { it.superseded || it.pendingDate || it.hiddenAtCredit }.forEach { allocation ->
        val date = allocation.date ?: return@forEach
        add(date, allocation.minutes, allocation.goalAtCredit)
    }

    val storedByDate = stored.associateBy { it.date }
    val earliestActivityDate = totalsByDate.keys.minOrNull() ?: return emptyList()

    // Every date canonical activity can speak to, plus stored rows after that activity begins whose
    // minutes have since been re-attributed away and must fall to zero.
    val dates = (totalsByDate.keys + storedByDate.keys)
        .filter { it >= earliestActivityDate }
        .distinct()
        .sorted()

    return dates.mapNotNull { date ->
        val corrected = totalsByDate[date]?.minutes?.let(Math::toIntExact) ?: 0
        val correctedGoal = totalsByDate[date]?.goals?.let(Math::toIntExact) ?: 0
        val row = storedByDate[date]
        if (row != null && row.minutesPlayed == corrected && row.goalMinutesPlayed == correctedGoal) {
            null // already agrees; nothing to rewrite
        } else {
            DailyProgressCorrection(
                date = date,
                storedMinutes = row?.minutesPlayed ?: 0,
                correctedMinutes = corrected,
                correctedGoalMinutes = correctedGoal,
            )
        }
    }
}

/**
 * Recompute legacy per-day totals from their canonical dated inputs: session remainders use the
 * start-date attribution rule, funded timed shares use their frozen credit date and unknown-time
 * allocations use their frozen date (auditfix-day-attribution / recover-missed-playtime Decision 7).
 *
 * Per-session attribution fixes what the sync records going forward; it does not revisit rows
 * already written. Every date recorded under the superseded poll-time rule therefore keeps a total
 * that its own sessions contradict — a midnight-crossing session shows under the day it began while
 * its minutes sit on the following day's row.
 *
 * Recovery rows are included in the same computation so an offline startup backfill cannot erase
 * imported or already-materialized bucket credit. Dates before the first canonical activity are
 * left untouched — the first sync baselines the library without synthesizing sessions, so rebuilding
 * those would write a zero and report an absence of records as an absence of play.
 *
 * `goalMinutesPlayed` is recomputed against today's Focus flags because nothing records what
 * `isGoal` was on a past date. That is the same basis History already displays, so the two agree
 * afterwards; it is not a faithful replay and is not claimed as one.
 */
class DailyProgressBackfillUseCase @Inject constructor(
    private val sessionDao: SessionDao,
    private val recoveryDao: PlaytimeRecoveryDao,
    private val gameDao: GameDao,
    private val dailyProgressDao: DailyProgressDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val syncCoordinator: SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val dailyCredit: RecoveryDailyCreditCoordinator,
) {

    /**
     * Apply the correction if it has not run before.
     *
     * @return the corrections written, or `null` if this call was a no-op because the backfill had
     *   already been applied.
     */
    suspend operator fun invoke(): List<DailyProgressCorrection>? = syncCoordinator.withLock {
        if (settings.dailyProgressBackfilled()) return@withLock null

        derivedStateWrites.withLock {
            val corrections = computeCorrections()
            corrections.forEach { correction ->
                dailyProgressDao.ensureDate(correction.date)
                dailyProgressDao.setMinutes(
                    date = correction.date,
                    minutesPlayed = correction.correctedMinutes,
                    goalMinutesPlayed = correction.correctedGoalMinutes,
                )
            }

            // Quest status and streaks are derived from the totals just rewritten, so they have to
            // be re-derived here rather than waiting for the next sync — which may never come
            // offline. BACKFILL, not SYNC: this transition is a baseline correction, so it must not
            // be delivered to the player as earned progress.
            val rules = settings.ruleConfigWithVersionFlow.first()
            gamificationUpdater.recompute(
                today = time.today(),
                source = RecomputeSource.BACKFILL,
                config = rules.config,
                configVersion = rules.version,
            )

            // The guard is the final write: a failed rule read or recompute leaves the correction
            // retryable on the next launch instead of marking derived state permanently complete.
            settings.setDailyProgressBackfilled(true)
            corrections
        }
    }

    /** The corrections this backfill would write, without writing them. */
    suspend fun computeCorrections(accountOverride: String? = null): List<DailyProgressCorrection> {
        val account = accountOverride ?: recoveryDao.activeAccount()
        return dailyProgressCorrections(
            sessions = sessionDao.getAll(),
            goalAppIds = gameDao.getAll().filter { it.isGoal }.mapTo(mutableSetOf()) { it.appId },
            stored = dailyProgressDao.getAllOrdered(),
            zone = time.zone(),
            timedShares = account?.let { recoveryDao.activeTimedPlacementsForAccount(it) }.orEmpty(),
            allocations = account?.let { recoveryDao.activeAllocationsForAccount(it) }.orEmpty(),
        )
    }

    /**
     * Apply one placement-revision move to the daily rows and re-evaluate the affected dates' quests
     * under corrective provenance (recover-missed-playtime design.md §7, task 5.3).
     *
     * Only the *operation's own* shares move: [before] is the superseded revision's active
     * placement graph, [after] the new revision's. The stored remainder of every affected date stays
     * untouched (deltas, never a rebuild), frozen credit-time hidden/Focus eligibility decides each
     * share's contribution, and the recompute under [source] lets a quest flip in either direction
     * without generating a new edge event. A mixed sync that also carries fresh earned inputs must
     * use the raw half ([applyPlacementCorrections]) and then run its staged finalization
     * ([RecoveryDerivedFinalizer]) — this method would otherwise absorb the fresh inputs into a
     * single corrective pass.
     *
     * @return the signed deltas actually applied to the daily rows.
     */
    suspend fun applyPlacementCorrectionsWithRecompute(
        before: List<PlacementShare>,
        after: List<PlacementShare>,
        source: RecomputeSource = RecomputeSource.RETROACTIVE_PLAY,
    ): List<DailyCreditDelta> = syncCoordinator.withLock {
        derivedStateWrites.withLock {
            val applied = dailyCredit.applyPlacementCorrections(before, after)
            if (applied.isNotEmpty()) {
                val rules = settings.ruleConfigWithVersionFlow.first()
                gamificationUpdater.recompute(
                    today = time.today(),
                    source = source,
                    config = rules.config,
                    configVersion = rules.version,
                )
            }
            applied
        }
    }

    /**
     * The raw half of a placement correction: write the signed deltas between [before] and [after]
     * without any recompute. Used by the mixed protocol, where the staged
     * [RecoveryDerivedFinalizer] owns the derived passes and this method must run inside the raw
     * commit's transaction.
     *
     * @return the signed deltas actually applied to the daily rows.
     */
    suspend fun applyPlacementCorrections(
        before: List<PlacementShare>,
        after: List<PlacementShare>,
    ): List<DailyCreditDelta> = dailyCredit.applyPlacementCorrections(before, after)
}
