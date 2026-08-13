package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.domain.ProgressMarksStore
import com.example.backlogium.domain.ProgressTransitionCoordinator
import com.example.backlogium.domain.STREAK_MILESTONE_INTERVAL_DAYS
import com.example.backlogium.domain.inPresentationOrder
import com.example.backlogium.domain.isStreakMilestone
import com.example.backlogium.domain.laterOf
import com.example.backlogium.domain.resolvePendingTransition
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Durable delivery seam for player-facing progress events.
 *
 * Two kinds of pending state, deliberately not treated alike:
 *
 * - `LevelUp` and `StreakMilestone` are *reconstructed* by comparing Room's live derived values
 *   against the acknowledged high-water marks. Both compare a monotonic value to its baseline, so
 *   the comparison is idempotent — but it is only meaningful when Room and the marks describe the
 *   same logical version of state.
 * - `QuestMet` and `StreakBroken` are read straight out of the marks' explicit pending identity,
 *   written only by an earned transition. Nothing about them is inferred from stored rows, which is
 *   what keeps a historical `questMet = true` row — one that predates tracking, or that a rule
 *   change flipped — from being mistaken for an earned, undelivered quest.
 *
 * Acknowledgement advances only the mark for the event that was actually shown.
 */
@Singleton
class ProgressEventRepository @Inject constructor(
    private val marksStore: ProgressMarksStore,
    private val profileDao: PlayerProfileDao,
    private val dailyProgressDao: DailyProgressDao,
    private val transitionCoordinator: ProgressTransitionCoordinator,
) {
    val pendingEvents: Flow<List<ProgressEvent>> = flow {
        // A leftover pending transition from a persist() that crashed before finalizing its marks
        // must be resolved before any comparison below, or a non-earned write's Room-only half
        // would read as earned progress. Acquires the coordinator, so a persist() that is merely
        // in-flight is waited for rather than treated as abandoned. Cheap once nothing is pending.
        resolvePendingTransition(transitionCoordinator, marksStore, profileDao, dailyProgressDao)
        emitAll(
            combine(
                marksStore.marks,
                profileDao.observe(),
            ) { marks, profile ->
                if (!marks.initialized || profile == null) {
                    emptyList()
                } else {
                    buildList {
                        // A pending transition means a persist() is between its write-ahead record
                        // and its finalize: Room and the marks are knowingly describing different
                        // logical versions of state, and that pair is not a valid thing to diff.
                        // Reconstruction resumes once the coordinator's owner finalizes (or recovery
                        // resolves) the transition — which re-emits here, because the marks change.
                        if (marks.pendingTransition == null) {
                            if (profile.level > marks.lastCelebratedLevel) {
                                add(ProgressEvent.LevelUp(marks.lastCelebratedLevel, profile.level))
                            }

                            val highestMilestone = highestMilestoneAtOrBelow(profile.currentStreak)
                            if (highestMilestone > marks.lastCelebratedStreakMilestone) {
                                add(ProgressEvent.StreakMilestone(highestMilestone))
                            }
                        }

                        // Both durable slots below are written only by the finalize step, so during
                        // an in-flight transition they still hold their last finalized value — a
                        // self-consistent record of something already earned, not a half-committed
                        // pair. Suppressing them would delay a delivery that is already owed.
                        //
                        // One quest at a time, oldest first: the rest stay pending until this one is
                        // acknowledged, so a later day can never hide an earlier one.
                        marks.pendingQuestDates.minOrNull()?.let {
                            add(ProgressEvent.QuestMet(it))
                        }

                        marks.pendingStreakBreak?.let { pending ->
                            if (pending.previousLength > 0) {
                                add(ProgressEvent.StreakBroken(pending.previousLength))
                            }
                        }
                    }.inPresentationOrder()
                }
            },
        )
    }

    /** Advance only the delivery mark corresponding to [event], after it has been presented. */
    suspend fun acknowledge(event: ProgressEvent) {
        marksStore.update { marks ->
            when (event) {
                is ProgressEvent.LevelUp -> marks.copy(
                    lastCelebratedLevel = maxOf(marks.lastCelebratedLevel, event.to),
                    initialized = true,
                )
                is ProgressEvent.StreakMilestone -> marks.copy(
                    lastCelebratedStreakMilestone = maxOf(
                        marks.lastCelebratedStreakMilestone,
                        event.days,
                    ),
                    initialized = true,
                )
                // Exactly the date presented: any other pending date is owed its own delivery, and
                // the high-water mark only advances, so acknowledging an older day cannot regress
                // past a newer one. Removing an absent date is a no-op, so this is idempotent.
                is ProgressEvent.QuestMet -> marks.copy(
                    lastQuestCelebratedDate = laterOf(marks.lastQuestCelebratedDate, event.date),
                    pendingQuestDates = marks.pendingQuestDates - event.date,
                    initialized = true,
                )
                is ProgressEvent.StreakBroken -> {
                    val pending = marks.pendingStreakBreak
                    marks.copy(
                        lastStreakBrokenDate = pending?.date ?: marks.lastStreakBrokenDate,
                        pendingStreakBreak = null,
                        initialized = true,
                    )
                }
            }
        }
    }

    private fun highestMilestoneAtOrBelow(streak: Int): Int {
        if (streak <= 0) return 0
        val candidate = streak - (streak % STREAK_MILESTONE_INTERVAL_DAYS)
        return candidate.takeIf(::isStreakMilestone) ?: 0
    }
}
