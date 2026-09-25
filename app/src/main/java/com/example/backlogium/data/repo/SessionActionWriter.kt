package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.HiddenGameDao
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
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
    private val hiddenGameDao: HiddenGameDao,
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
     *
     * @return the actions that actually changed persisted state, with [SessionDiffer.SessionAction.Extend]
     *   minute deltas trimmed to what was newly stored. A stale Extend already fully persisted
     *   contributes nothing here, so crediting this list cannot double-count it even when a later
     *   action in the same batch still writes.
     */
    suspend fun applySessionActions(
        actions: List<SessionDiffer.SessionAction>,
        recoveredFromCloud: Boolean = false,
        timingInformedSessionKeys: Set<Pair<Long, Long>> = emptySet(),
    ): List<SessionDiffer.SessionAction> {
        val effective = mutableListOf<SessionDiffer.SessionAction>()
        for ((index, action) in actions.withIndex()) {
            when (action) {
                is SessionDiffer.SessionAction.Open -> {
                    val opened = sessionDao.tryOpenSession(
                        appId = action.appId,
                        startAt = action.startAt,
                        endAt = action.endAt,
                        minutes = action.minutes,
                        recoveredSharedPlay = if (recoveredFromCloud && action.addedMinutes > 0) {
                            RecoveredSharedPlayState.FULL
                        } else {
                            RecoveredSharedPlayState.NONE
                        },
                        timingInformedSteamPlay = if (action.appId to action.startAt in timingInformedSessionKeys) {
                            TimingInformedSteamPlayState.FULL
                        } else {
                            TimingInformedSteamPlayState.NONE
                        },
                    )
                    val historicalClose = if (opened == -1L) {
                        historicalBackfillClose(actions, index, action)
                    } else {
                        null
                    }
                    if (opened != -1L) {
                        effective += action
                    } else if (historicalClose != null) {
                        // Historical backfill while a newer session is still open: persist the
                        // completed interval closed, so even the intermediate write respects the
                        // database's one-open-session index.
                        sessionDao.insert(
                            Session(
                                appId = action.appId,
                                startAt = action.startAt,
                                endAt = historicalClose.endAt,
                                minutes = action.minutes,
                                open = false,
                                recoveredSharedPlay = if (recoveredFromCloud && action.addedMinutes > 0) {
                                    RecoveredSharedPlayState.FULL
                                } else {
                                    RecoveredSharedPlayState.NONE
                                },
                                timingInformedSteamPlay = if (action.appId to action.startAt in timingInformedSessionKeys) {
                                    TimingInformedSteamPlayState.FULL
                                } else {
                                    TimingInformedSteamPlayState.NONE
                                },
                            ),
                        )
                        effective += action
                    } else {
                        // Lost the race: a concurrent caller already opened this game's session. That
                        // observation is not wrong, only late — fold it into the session that won
                        // rather than dropping it, which is what the second observation actually meant.
                        sessionDao.getOpenSession(action.appId)?.let {
                            sessionDao.update(
                                it.copy(
                                    // Deterministic in either commit order: the merged row keeps
                                    // the earlier observed start and the latest observed end, so
                                    // two concurrent Opens leave identical stored state
                                    // regardless of which insert won (auditfix-session-ledger-
                                    // integrity, #116). Future presence extensions measure from
                                    // this start, so a winner-dependent start would also diverge
                                    // later minute calculation.
                                    startAt = minOf(it.startAt, action.startAt),
                                    minutes = it.minutes + action.addedMinutes,
                                    endAt = maxOf(it.endAt ?: it.startAt, action.endAt),
                                    recoveredSharedPlay = it.recoveryAfter(
                                        recoveredFromCloud, action.addedMinutes,
                                    ),
                                    timingInformedSteamPlay = it.timingAfter(
                                        action.appId to action.startAt in timingInformedSessionKeys,
                                        action.addedMinutes,
                                    ),
                                ),
                            )
                            effective += action
                        }
                    }
                }

                is SessionDiffer.SessionAction.Extend -> {
                    // Match by start: the session this action was derived from. Touching any open
                    // regardless of start would fold a stale or historical Extend into an unrelated
                    // live session.
                    sessionDao.getAll().asSequence()
                        .filter { it.appId == action.appId && it.startAt == action.startAt }
                        .maxByOrNull { it.id }?.let {
                        val endAt = maxOf(it.endAt ?: it.startAt, action.endAt)
                        val minutes = maxOf(it.minutes, action.minutes)
                        if (endAt != it.endAt || minutes != it.minutes || !it.open) {
                            sessionDao.update(
                                it.copy(
                                    minutes = minutes,
                                    endAt = endAt,
                                    open = true,
                                    openAppId = it.appId,
                                    recoveredSharedPlay = it.recoveryAfter(
                                        recoveredFromCloud, minutes - it.minutes,
                                    ),
                                    timingInformedSteamPlay = it.timingAfter(
                                        action.appId to action.startAt in timingInformedSessionKeys,
                                        minutes - it.minutes,
                                    ),
                                ),
                            )
                            effective += action.copy(
                                minutes = minutes,
                                endAt = endAt,
                                addedMinutes = minutes - it.minutes,
                            )
                        }
                    }
                }

                is SessionDiffer.SessionAction.Close ->
                    // Match by start so a stale Close cannot close an unrelated live session, and a
                    // historical backfill closes the row it opened rather than the live one.
                    sessionDao.getAll()
                        .firstOrNull { it.appId == action.appId && it.open && it.startAt == action.startAt }
                        ?.let {
                        sessionDao.update(
                            it.copy(open = false, openAppId = null, endAt = action.endAt),
                        )
                        effective += action
                    }
            }
        }
        return effective
    }

    /** Credit each action's newly observed minutes to the local date its session started on. */
    suspend fun creditDailyProgress(
        actions: List<SessionDiffer.SessionAction>,
        goalAppIds: Set<Long>,
    ) {
        val hiddenIds = hiddenGameDao.hiddenAppIds().toSet()
        attributeDailyProgress(actions, goalAppIds, time.zone(), hiddenIds).forEach { (date, credit) ->
            dailyProgressDao.ensureDate(date)
            dailyProgressDao.addMinutes(date, credit.minutesPlayed, credit.goalMinutesPlayed)
        }
    }

    /** Both halves together — the presence path's whole write. */
    suspend fun apply(
        actions: List<SessionDiffer.SessionAction>,
        goalAppIds: Set<Long>,
        recoveredFromCloud: Boolean = false,
    ): List<SessionDiffer.SessionAction> {
        if (actions.isEmpty()) return emptyList()
        return transaction.run {
            val effective = applySessionActions(actions, recoveredFromCloud)
            if (effective.isNotEmpty()) creditDailyProgress(effective, goalAppIds)
            effective
        }
    }

    private fun Session.recoveryAfter(cloud: Boolean, addedMinutes: Int): RecoveredSharedPlayState? {
        if (addedMinutes <= 0) return recoveredSharedPlay
        if (!cloud) return if (recoveredSharedPlay == RecoveredSharedPlayState.FULL) {
            RecoveredSharedPlayState.PARTIAL
        } else {
            recoveredSharedPlay
        }
        return when {
            minutes == 0 -> RecoveredSharedPlayState.FULL
            recoveredSharedPlay == RecoveredSharedPlayState.FULL -> RecoveredSharedPlayState.FULL
            else -> RecoveredSharedPlayState.PARTIAL
        }
    }

    private fun Session.timingAfter(informed: Boolean, addedMinutes: Int): TimingInformedSteamPlayState? {
        if (addedMinutes <= 0) return timingInformedSteamPlay
        if (!informed) return if (timingInformedSteamPlay == TimingInformedSteamPlayState.FULL) {
            TimingInformedSteamPlayState.PARTIAL
        } else {
            timingInformedSteamPlay
        }
        return if (minutes == 0 || timingInformedSteamPlay == TimingInformedSteamPlayState.FULL) {
            TimingInformedSteamPlayState.FULL
        } else {
            TimingInformedSteamPlayState.PARTIAL
        }
    }

    private suspend fun historicalBackfillClose(
        actions: List<SessionDiffer.SessionAction>,
        index: Int,
        action: SessionDiffer.SessionAction.Open,
    ): SessionDiffer.SessionAction.Close? {
        val earliestOpenStart = sessionDao.getAll()
            .asSequence()
            .filter { it.appId == action.appId && it.open }
            .minOfOrNull { it.startAt }
            ?: return null
        if (action.endAt >= earliestOpenStart) return null
        // Only a closed-to-be row takes the separate path. A live Open with no following Close
        // is the concurrent-observation race (#116), which must still merge even when it happens
        // to sort earlier.
        return actions.subList(index + 1, actions.size)
            .filterIsInstance<SessionDiffer.SessionAction.Close>()
            .firstOrNull {
                it.appId == action.appId &&
                    it.startAt == action.startAt
            }
    }
}
