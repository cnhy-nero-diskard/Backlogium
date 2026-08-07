package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CollectionPacingTest {

    private val today = LocalDate.parse("2026-08-07")
    private val reliablePace = PersonalPace.derive(
        (1..28).map { age -> DatedPlayTotal(today.minusDays(age.toLong()), 60) },
        today,
    )

    @Test
    fun deadlineStates_andInterventionFollowConfidenceCompletenessAndCapacity() {
        val onTrack = deriveDeadline(
            target = today.plusDays(3),
            estimate = 100,
            pace = reliablePace,
        )
        val atRisk = deriveDeadline(
            target = today.plusDays(3),
            estimate = 1_000,
            pace = reliablePace,
        )
        val learning = deriveDeadline(
            target = today.plusDays(3),
            estimate = 100,
            pace = PersonalPaceProfile.empty(),
        )
        val incomplete = deriveDeadline(
            target = today.plusDays(3),
            estimate = null,
            pace = reliablePace,
        )

        assertEquals(CollectionPacingState.ON_TRACK, onTrack.pacingState)
        assertFalse(onTrack.deadlineInterventionEligible)
        assertTrue(onTrack.capacityMarginMinutes!! > 0.0)

        assertEquals(CollectionPacingState.AT_RISK, atRisk.pacingState)
        assertTrue(atRisk.deadlineInterventionEligible)
        assertNotNull(atRisk.requiredMinutesPerActiveDay)
        assertNotNull(atRisk.estimatedFitDate)

        assertEquals(CollectionPacingState.LEARNING, learning.pacingState)
        assertFalse(learning.deadlineInterventionEligible)

        assertEquals(CollectionPacingState.INCOMPLETE_DATA, incomplete.pacingState)
        assertFalse(incomplete.deadlineInterventionEligible)
        assertEquals(1, incomplete.unknownDurationCount)
    }

    @Test
    fun arrivedOrPassedUnfinishedDeadlineIsEligible_evenWhenHistoryIsLearning() {
        val arrived = deriveDeadline(
            target = today,
            estimate = 100,
            pace = PersonalPaceProfile.empty(),
        )
        val passed = deriveDeadline(
            target = today.minusDays(2),
            estimate = 100,
            pace = PersonalPaceProfile.empty(),
        )
        val completed = deriveDeadline(
            target = today.minusDays(2),
            estimate = 100,
            playtime = 100,
            pace = PersonalPaceProfile.empty(),
        )

        assertTrue(arrived.deadlinePassed)
        assertTrue(arrived.deadlineInterventionEligible)
        assertTrue(passed.deadlineInterventionEligible)
        assertFalse(completed.deadlineInterventionEligible)
    }

    @Test
    fun completionAndQueueUseCompletionistHorizon_whileBasicHasNoPacing() {
        val completion = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionSort.COMPLETION_FRACTION,
            targetDate = null,
            members = listOf(member(completionist = 180)),
            today = today,
            personalPace = reliablePace,
        )
        val queue = CollectionSummary.derive(
            mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionSort.MANUAL_SEQUENCE,
            targetDate = null,
            members = listOf(
                member(appId = 1, name = "Done", completionist = null, manualDone = true),
                member(appId = 2, name = "Next", completionist = 120),
            ),
            today = today,
            personalPace = reliablePace,
        )
        val basic = CollectionSummary.derive(
            mode = CollectionMode.BASIC,
            sort = CollectionSort.NAME,
            targetDate = null,
            members = listOf(member(completionist = 180)),
            today = today,
            personalPace = reliablePace,
        )

        assertEquals(CollectionPacingState.ON_TRACK, completion.pacingState)
        assertNotNull(completion.completionHorizonDate)
        assertEquals(CollectionPacingState.ON_TRACK, queue.pacingState)
        assertEquals("Next", queue.nextUp?.name)
        assertNotNull(queue.nextGameHorizonDate)
        assertNotNull(queue.queueHorizonDate)
        assertNull(basic.pacingState)
        assertNull(basic.remainingMinutes)
        assertNull(basic.completionHorizonDate)
    }

    private fun deriveDeadline(
        target: LocalDate,
        estimate: Int?,
        playtime: Int = 0,
        pace: PersonalPaceProfile,
    ): CollectionBanner = CollectionSummary.derive(
        mode = CollectionMode.DEADLINE_GOAL,
        sort = CollectionSort.DAYS_REMAINING,
        targetDate = target,
        members = listOf(
            member(
                playtime = playtime,
                mainStory = estimate,
                mainExtra = estimate,
                completionist = estimate,
                allStyles = estimate,
            ),
        ),
        today = today,
        timeBasis = CollectionTimeBasis.MAIN_STORY,
        personalPace = pace,
    )

    private fun member(
        appId: Long = 1L,
        name: String = "Game $appId",
        playtime: Int = 0,
        mainStory: Int? = null,
        mainExtra: Int? = null,
        completionist: Int? = null,
        allStyles: Int? = null,
        manualDone: Boolean = false,
    ) = CollectionMemberSignals(
        appId = appId,
        name = name,
        playtimeMinutes = playtime,
        completionistMinutes = completionist,
        mainStoryMinutes = mainStory,
        mainExtraMinutes = mainExtra,
        allStylesMinutes = allStyles,
        achievementsUnlocked = null,
        achievementsTotal = null,
        manualDone = manualDone,
    )
}
