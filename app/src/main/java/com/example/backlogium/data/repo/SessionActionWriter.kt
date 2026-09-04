package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.attributeDailyProgress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one path session actions take into storage, whichever mechanism produced them.
 *
 * Both [SessionDiffer] and [com.example.backlogium.domain.PresenceSessionDeriver] return the same
 * action vocabulary, and both come through here — which is what makes a presence-derived session
 * indistinguishable from a diffed one everywhere downstream: same table, same open/closed
 * convention, same daily-progress crediting, so the same XP, quests, streaks, history and
 * analytics apply without any of them knowing which mechanism ran.
 *
 * The caller owns the transaction. The sync worker calls this inside its existing raw-commit
 * boundary; the presence path has no larger commit to join and calls it directly.
 */
@Singleton
class SessionActionWriter @Inject constructor(
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val time: TimeProvider,
    private val transaction: DatabaseTransactionScope = com.example.backlogium.data.backup.PassThroughTransactionScope,
) {

    /**
     * Apply the session rows only, leaving daily-progress crediting to the caller.
     *
     * This is the one write boundary every session action passes through regardless of caller —
     * [com.example.backlogium.domain.PlaytimeObservationCommitter] and [PresenceSessionRecorder]
     * both route their actions here — which is what makes the [SessionDao.tryOpenSession] guard
     * below hold "regardless of which caller reaches it" (auditfix-session-ledger-integrity, #116).
     */
    suspend fun applySessionActions(actions: List<SessionDiffer.SessionAction>) {
        for (action in actions) {
            when (action) {
                is SessionDiffer.SessionAction.Open -> {
                    val opened = sessionDao.tryOpenSession(
                        appId = action.appId,
                        startAt = action.startAt,
                        endAt = action.endAt,
                        minutes = action.minutes,
                    )
                    // Lost the race: a concurrent caller already opened this game's session. That
                    // observation is not wrong, only late — fold it into the session that won
                    // rather than dropping it, which is what the second observation actually meant.
                    if (opened == -1L) {
                        sessionDao.getOpenSession(action.appId)?.let {
                            sessionDao.update(
                                it.copy(
                                    minutes = it.minutes + action.addedMinutes,
                                    endAt = maxOf(it.endAt ?: it.startAt, action.endAt),
                                ),
                            )
                        }
                    }
                }

                is SessionDiffer.SessionAction.Extend ->
                    sessionDao.getOpenSession(action.appId)?.let {
                        sessionDao.update(it.copy(minutes = action.minutes, endAt = action.endAt))
                    }

                is SessionDiffer.SessionAction.Close ->
                    sessionDao.getOpenSession(action.appId)?.let {
                        sessionDao.update(it.copy(open = false, endAt = action.endAt))
                    }
            }
        }
    }

    /** Credit each action's newly observed minutes to the local date its session started on. */
    suspend fun creditDailyProgress(
        actions: List<SessionDiffer.SessionAction>,
        goalAppIds: Set<Long>,
    ) {
        attributeDailyProgress(actions, goalAppIds, time.zone()).forEach { (date, credit) ->
            dailyProgressDao.ensureDate(date)
            dailyProgressDao.addMinutes(date, credit.minutesPlayed, credit.goalMinutesPlayed)
        }
    }

    /** Both halves together — the presence path's whole write. */
    suspend fun apply(actions: List<SessionDiffer.SessionAction>, goalAppIds: Set<Long>) {
        if (actions.isEmpty()) return
        transaction.run {
            applySessionActions(actions)
            creditDailyProgress(actions, goalAppIds)
        }
    }
}
