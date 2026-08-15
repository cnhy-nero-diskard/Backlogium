package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Session
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
 * The whole decision, as a pure function: which dates disagree with the session ledger, and what
 * they should hold instead. Returns only the dates that need rewriting.
 *
 * Kept separate from [DailyProgressBackfillUseCase] so the rule can be exercised without a
 * DataStore, a recompute, or the one-shot guard that makes applying it unrepeatable.
 *
 * [stored] may contain dates the ledger cannot speak to; those before the earliest session are
 * excluded rather than zeroed.
 */
internal fun dailyProgressCorrections(
    sessions: List<Session>,
    goalAppIds: Set<Long>,
    stored: List<DailyProgress>,
    zone: ZoneId,
): List<DailyProgressCorrection> {
    if (sessions.isEmpty()) return emptyList()

    fun dateOf(session: Session): String =
        Instant.ofEpochMilli(session.startAt).atZone(zone).toLocalDate().toString()

    val sessionsByDate = sessions.groupBy(::dateOf)
    val storedByDate = stored.associateBy { it.date }
    val earliestSessionDate = sessionsByDate.keys.min()

    // Every date the ledger can speak to: those with sessions, plus stored rows at or after the
    // ledger begins whose minutes have since been re-attributed away and must fall to zero.
    val dates = (sessionsByDate.keys + storedByDate.keys)
        .filter { it >= earliestSessionDate }
        .distinct()
        .sorted()

    return dates.mapNotNull { date ->
        val daySessions = sessionsByDate[date].orEmpty()
        val corrected = daySessions.sumOf { it.minutes }
        val correctedGoal = daySessions.filter { it.appId in goalAppIds }.sumOf { it.minutes }
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
 * Recompute per-day totals from the sessions that produced them, under the start-date attribution
 * rule (auditfix-day-attribution Decision 7).
 *
 * Per-session attribution fixes what the sync records going forward; it does not revisit rows
 * already written. Every date recorded under the superseded poll-time rule therefore keeps a total
 * that its own sessions contradict — a midnight-crossing session shows under the day it began while
 * its minutes sit on the following day's row.
 *
 * Safe because sessions are append-only: nothing in the app deletes a session, so for any date at
 * or after the first session the ledger is complete and this recomputation is authoritative rather
 * than lossy. Dates *before* the first session are left untouched — the first sync baselines the
 * library without synthesizing sessions, so rebuilding those would write a zero and report an
 * absence of records as an absence of play.
 *
 * `goalMinutesPlayed` is recomputed against today's Focus flags because nothing records what
 * `isGoal` was on a past date. That is the same basis History already displays, so the two agree
 * afterwards; it is not a faithful replay and is not claimed as one.
 */
class DailyProgressBackfillUseCase @Inject constructor(
    private val sessionDao: SessionDao,
    private val gameDao: GameDao,
    private val dailyProgressDao: DailyProgressDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val syncCoordinator: SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
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
    suspend fun computeCorrections(): List<DailyProgressCorrection> = dailyProgressCorrections(
        sessions = sessionDao.getAll(),
        goalAppIds = gameDao.getAll().filter { it.isGoal }.mapTo(mutableSetOf()) { it.appId },
        stored = dailyProgressDao.getAllOrdered(),
        zone = time.zone(),
    )
}
