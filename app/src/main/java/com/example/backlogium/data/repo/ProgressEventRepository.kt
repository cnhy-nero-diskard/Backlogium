package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.domain.ProgressMarksStore
import com.example.backlogium.domain.STREAK_MILESTONE_INTERVAL_DAYS
import com.example.backlogium.domain.inPresentationOrder
import com.example.backlogium.domain.isStreakMilestone
import com.example.backlogium.domain.resolvePendingTransition
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Durable delivery seam for player-facing progress events.
 *
 * Pending state is reconstructed from Room's current derived values and the acknowledged
 * high-water marks. Acknowledgement advances only the mark for the event that was actually shown.
 */
@Singleton
class ProgressEventRepository @Inject constructor(
    private val marksStore: ProgressMarksStore,
    private val profileDao: PlayerProfileDao,
    private val dailyProgressDao: DailyProgressDao,
) {
    val pendingEvents: Flow<List<ProgressEvent>> = flow {
        // A leftover pending transition from a persist() that crashed before finalizing its marks
        // must be resolved before any comparison below, or a non-earned write's Room-only half
        // would read as earned progress. Cheap no-op once nothing is pending.
        resolvePendingTransition(marksStore, profileDao, dailyProgressDao)
        emitAll(
            combine(
                marksStore.marks,
                profileDao.observe(),
                dailyProgressDao.observeAll(),
            ) { marks, profile, days ->
                if (!marks.initialized || profile == null) {
                    emptyList()
                } else {
                    buildList {
                        if (profile.level > marks.lastCelebratedLevel) {
                            add(ProgressEvent.LevelUp(marks.lastCelebratedLevel, profile.level))
                        }

                        val highestMilestone = highestMilestoneAtOrBelow(profile.currentStreak)
                        if (highestMilestone > marks.lastCelebratedStreakMilestone) {
                            add(ProgressEvent.StreakMilestone(highestMilestone))
                        }

                        earliestUnacknowledgedQuestDate(days, marks.lastQuestCelebratedDate)?.let {
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
                is ProgressEvent.QuestMet -> marks.copy(
                    lastQuestCelebratedDate = maxOfDate(marks.lastQuestCelebratedDate, event.date),
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

    /**
     * The earliest date with a met, unacknowledged quest — strictly after [acknowledgedThrough],
     * or any met date at all when nothing has been acknowledged yet. Scanning by date rather than
     * asking only about "today" is what lets a quest earned on a day the app never reopened stay
     * deliverable, and returning the earliest (not the latest) is what keeps a second
     * unacknowledged day from silently hiding an earlier one.
     */
    private fun earliestUnacknowledgedQuestDate(
        days: List<DailyProgress>,
        acknowledgedThrough: LocalDate?,
    ): LocalDate? = days
        .asSequence()
        .filter { it.questMet }
        .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        .filter { acknowledgedThrough == null || it > acknowledgedThrough }
        .minOrNull()

    private fun highestMilestoneAtOrBelow(streak: Int): Int {
        if (streak <= 0) return 0
        val candidate = streak - (streak % STREAK_MILESTONE_INTERVAL_DAYS)
        return candidate.takeIf(::isStreakMilestone) ?: 0
    }

    private fun maxOfDate(first: java.time.LocalDate?, second: java.time.LocalDate): java.time.LocalDate =
        if (first == null || second > first) second else first
}
