package com.example.backlogium.data.repo

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.domain.STREAK_MILESTONE_INTERVAL_DAYS
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.inPresentationOrder
import com.example.backlogium.domain.isStreakMilestone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Durable delivery seam for player-facing progress events.
 *
 * Pending state is reconstructed from Room's current derived values and DataStore's acknowledged
 * high-water marks. Acknowledgement advances only the mark for the event that was actually shown.
 */
@Singleton
class ProgressEventRepository @Inject constructor(
    private val settings: SettingsDataStore,
    private val profileDao: PlayerProfileDao,
    private val dailyProgressDao: DailyProgressDao,
    private val time: TimeProvider,
) {
    val pendingEvents: Flow<List<ProgressEvent>> = combine(
        settings.progressMarksFlow,
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

                val today = time.today()
                val todayMet = days.firstOrNull { it.date == today.toString() }?.questMet == true
                if (todayMet && marks.lastQuestCelebratedDate != today) {
                    add(ProgressEvent.QuestMet(today))
                }

                marks.pendingStreakBreak?.let { pending ->
                    if (pending.previousLength > 0) {
                        add(ProgressEvent.StreakBroken(pending.previousLength))
                    }
                }
            }.inPresentationOrder()
        }
    }

    /** Advance only the delivery mark corresponding to [event], after it has been presented. */
    suspend fun acknowledge(event: ProgressEvent) {
        val marks = settings.readProgressMarks()
        val next = when (event) {
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
        settings.writeProgressMarks(next)
    }

    private fun highestMilestoneAtOrBelow(streak: Int): Int {
        if (streak <= 0) return 0
        val candidate = streak - (streak % STREAK_MILESTONE_INTERVAL_DAYS)
        return candidate.takeIf(::isStreakMilestone) ?: 0
    }

    private fun maxOfDate(first: java.time.LocalDate?, second: java.time.LocalDate): java.time.LocalDate =
        if (first == null || second > first) second else first
}
