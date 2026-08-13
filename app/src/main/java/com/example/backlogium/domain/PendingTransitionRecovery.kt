package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao

/**
 * Resolves a leftover [PendingTransition] left by a [GamificationUpdater.persist] call that
 * crashed between writing its write-ahead record and finalizing its marks.
 *
 * Replays [ProgressEventDetector.detect] using the previous state recorded in the pending
 * transition against Room's current, durable truth — exactly what the interrupted `persist()`
 * call would have written, whether the crash landed before the Room write (in which case current
 * equals previous and this is a no-op) or after it (in which case the real transition, including a
 * `StreakBroken` that would otherwise be unrecoverable, is detected and delivered).
 *
 * Acquires [coordinator] first, because "a pending transition is present" is not by itself evidence
 * that the call which wrote it is dead — a `persist()` that is merely between its WAL and its
 * finalize looks identical from the outside. Only the coordinator can distinguish the two: while it
 * is held by a live persist, no recovery pass can observe that persist's WAL at all, so an abandoned
 * record is the only kind this function can ever see.
 *
 * Cheap when nothing is pending: acquiring an uncontended mutex plus a single marks read.
 */
suspend fun resolvePendingTransition(
    coordinator: ProgressTransitionCoordinator,
    marksStore: ProgressMarksStore,
    profileDao: PlayerProfileDao,
    dailyProgressDao: DailyProgressDao,
): ProgressMarks = coordinator.withTransition {
    resolvePendingTransitionWithinProtocol(marksStore, profileDao, dailyProgressDao)
}

/**
 * The recovery step itself, for callers that already own the protocol — [GamificationUpdater]'s own
 * critical section, which must not deadlock re-acquiring a non-reentrant coordinator. Every other
 * caller wants [resolvePendingTransition].
 */
internal suspend fun resolvePendingTransitionWithinProtocol(
    marksStore: ProgressMarksStore,
    profileDao: PlayerProfileDao,
    dailyProgressDao: DailyProgressDao,
): ProgressMarks {
    val marks = marksStore.read()
    val pending = marks.pendingTransition ?: return marks

    val profile = profileDao.get()
    val todayQuestMet = dailyProgressDao.getByDate(pending.evaluationDate.toString())?.questMet == true
    val previous = ProgressState(
        level = pending.previousLevel,
        currentStreak = pending.previousStreak,
        todayQuestMet = pending.previousTodayQuestMet,
    )
    val current = ProgressState(
        level = profile?.level ?: pending.previousLevel,
        currentStreak = profile?.currentStreak ?: pending.previousStreak,
        todayQuestMet = todayQuestMet,
    )

    return marksStore.update { live ->
        val livePending = live.pendingTransition
        if (livePending == null) {
            live
        } else {
            ProgressEventDetector.detect(
                marks = live,
                previous = previous,
                current = current,
                source = livePending.source,
                today = livePending.evaluationDate,
            ).marks.copy(pendingTransition = null)
        }
    }
}
