package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.ProgressEventRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quest delivery driven by explicit earned identity rather than by re-reading history.
 *
 * A stored `DailyProgress` row with `questMet = true` says only that the day's playtime satisfies
 * the *current* rule. It cannot say whether the player ever earned that transition while the feature
 * was watching, whether a rule change or import flipped it after the fact, or whether it was already
 * celebrated. These tests pin the cases where those differ.
 */
class QuestEventProvenanceTest {
    private val dayOne = LocalDate.parse("2026-08-12")
    private val dayTwo = LocalDate.parse("2026-08-13")

    @Test
    fun questEarnedBySyncStaysPendingAcrossDateRollover() = runTest {
        val harness = harness(days = listOf(day(dayOne, met = false)))

        harness.updater.persist(earnedQuest(dayOne), RecomputeSource.SYNC)
        assertEquals(setOf(dayOne), harness.marksStore.read().pendingQuestDates)

        // The next day's sync earns nothing: yesterday's undelivered quest is untouched, and its
        // identity is still yesterday's date.
        harness.updater.persist(
            progressResult(dayTwo, level = 1, questMet = false),
            RecomputeSource.SYNC,
        )

        assertEquals(setOf(dayOne), harness.marksStore.read().pendingQuestDates)
        assertEquals(
            ProgressEvent.QuestMet(dayOne),
            harness.repository.pendingEvents.first().single(),
        )
    }

    @Test
    fun aSecondSyncOnTheSameDayDoesNotDuplicateTheEarnedDate() = runTest {
        val harness = harness(days = listOf(day(dayOne, met = false)))

        harness.updater.persist(earnedQuest(dayOne), RecomputeSource.SYNC)
        harness.updater.persist(earnedQuest(dayOne), RecomputeSource.SYNC)

        assertEquals(setOf(dayOne), harness.marksStore.read().pendingQuestDates)
        assertEquals(
            ProgressEvent.QuestMet(dayOne),
            harness.repository.pendingEvents.first().single(),
        )
    }

    @Test
    fun nonEarnedSourcesTurningHistoryMetProduceNoPendingQuestDates() = runTest {
        for (source in RecomputeSource.entries.filter { it != RecomputeSource.SYNC }) {
            // A looser rule re-evaluates an old day as met. Nothing was earned; nothing is owed.
            val harness = harness(days = listOf(day(dayOne, met = false), day(dayTwo, met = false)))

            harness.updater.persist(
                progressResult(
                    dayTwo,
                    level = 1,
                    questMet = false,
                    changedDays = listOf(day(dayOne, met = true)),
                ),
                source,
            )

            val marks = harness.marksStore.read()
            assertEquals("$source synthesized a pending quest", emptySet<LocalDate>(), marks.pendingQuestDates)
            assertTrue(
                "$source produced a quest event",
                harness.repository.pendingEvents.first().none { it is ProgressEvent.QuestMet },
            )
        }
    }

    @Test
    fun anAcknowledgedQuestDoesNotReappearAfterANonEarnedRecomputeOnAnUnmetDay() = runTest {
        val harness = harness(days = listOf(day(dayOne, met = false)))

        harness.updater.persist(earnedQuest(dayOne), RecomputeSource.SYNC)
        val event = harness.repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.QuestMet(dayOne), event)
        harness.repository.acknowledge(event)

        // A rule change the next day, on a day whose own quest is unmet. The acknowledged day is
        // still met in Room — the exact shape that used to revive it, because the delivery baseline
        // was being reset to "no quest celebrated" whenever today's quest was not met.
        harness.updater.persist(
            progressResult(dayTwo, level = 1, questMet = false),
            RecomputeSource.RULE_CHANGE,
        )

        val marks = harness.marksStore.read()
        assertEquals(emptySet<LocalDate>(), marks.pendingQuestDates)
        assertEquals(dayOne, marks.lastQuestCelebratedDate)
        assertTrue(harness.repository.pendingEvents.first().isEmpty())
    }

    @Test
    fun firstInitializationDoesNotTurnHistoricalMetQuestsIntoEvents() = runTest {
        // A mature account adopting the feature: several past days already qualify, and today's does
        // too. The baseline is seeded from current state and nothing is celebrated retroactively.
        val harness = harness(
            marks = ProgressMarks(),
            profile = null,
            days = listOf(
                day(dayOne.minusDays(9), met = true),
                day(dayOne, met = true),
                day(dayTwo, met = true),
            ),
        )

        harness.updater.persist(
            progressResult(dayTwo, level = 12, streak = 7, questMet = true),
            RecomputeSource.SYNC,
        )

        val marks = harness.marksStore.read()
        assertTrue(marks.initialized)
        assertEquals(emptySet<LocalDate>(), marks.pendingQuestDates)
        assertEquals(dayTwo, marks.lastQuestCelebratedDate)
        assertTrue(harness.repository.pendingEvents.first().isEmpty())
    }

    private class Harness(
        val updater: GamificationUpdater,
        val repository: ProgressEventRepository,
        val marksStore: InMemoryProgressMarksStore,
        val profileDao: FakePlayerProfileDao,
    )

    /** Updater and repository sharing one marks store, coordinator, and set of DAOs. */
    private fun harness(
        marks: ProgressMarks = ProgressMarks(lastCelebratedLevel = 1, initialized = true),
        profile: PlayerProfile? = PlayerProfile(level = 1),
        days: List<DailyProgress> = emptyList(),
    ): Harness {
        val coordinator = ProgressTransitionCoordinator()
        val marksStore = InMemoryProgressMarksStore(marks)
        val profileDao = FakePlayerProfileDao(profile)
        val dailyDao = FakeDailyProgressDao(days)
        return Harness(
            updater = testUpdater(marksStore, coordinator, profileDao, dailyDao),
            repository = testRepository(marksStore, coordinator, profileDao, dailyDao),
            marksStore = marksStore,
            profileDao = profileDao,
        )
    }

    private fun day(date: LocalDate, met: Boolean) =
        DailyProgress(date = date.toString(), minutesPlayed = 40, questMet = met)

    /** A sync that flips [date]'s quest from unmet to met — the only shape that earns one. */
    private fun earnedQuest(date: LocalDate) = progressResult(
        date,
        level = 1,
        questMet = true,
        changedDays = listOf(day(date, met = true)),
    )
}
