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
 * Cheap when nothing is pending: a single marks read with no further I/O. Safe to call
 * defensively both at the start of [GamificationUpdater.persist] and at the start of
 * [com.example.backlogium.data.repo.ProgressEventRepository]'s pending-events flow, since neither
 * caller can otherwise guarantee the other has already run.
 */
suspend fun resolvePendingTransition(
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
