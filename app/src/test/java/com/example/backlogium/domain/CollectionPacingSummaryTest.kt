package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CollectionPacingSummaryTest {

    private val today = LocalDate.parse("2026-08-07")
    private val reliableProfile = PersonalPace.derive(
        (1..28).map { offset ->
            DatedPlayTotal(today.minusDays(offset.toLong()), 60)
        },
        today,
    )

    @Test
    fun deadlineStates_usePersonalPaceCapacityAndEligibility() {
        val onTrack = deriveDeadline(100)
        assertEquals(CollectionPacingState.ON_TRACK, onTrack.pacingState)
        assertTrue((onTrack.capacityMarginMinutes ?: -1.0) >= 0.0)
        assertFalse(onTrack.deadlineInterventionEligible)

        val atRisk = deriveDeadline(300)
        assertEquals(CollectionPacingState.AT_RISK, atRisk.pacingState)
        assertTrue((atRisk.capacityMarginMinutes ?: 0.0) < 0.0)
        assertTrue(atRisk.deadlineInterventionEligible)
        assertNotNull(atRisk.estimatedFitDate)
    }

    @Test
    fun futureLearningOrMissingEstimates_neverRecommendChangingDeadline() {
        val learning = deriveDeadline(
            estimateMinutes = 300,
            profile = PersonalPace.derive(
                listOf(DatedPlayTotal(today.minusDays(1), 60)),
                today,
            ),
        )
        assertEquals(CollectionPacingState.LEARNING, learning.pacingState)
        assertFalse(learning.deadlineInterventionEligible)
        assertNull(
            deriveDeadline(
                estimateMinutes = 300,
                profile = PersonalPaceProfile.empty(),
            ).recentTrackedPaceMinutes,
        )

        val incomplete = deriveDeadline(300, memberEstimateMinutes = null)
        assertEquals(CollectionPacingState.INCOMPLETE_DATA, incomplete.pacingState)
        assertEquals(1, incomplete.unknownDurationCount)
        assertFalse(incomplete.deadlineInterventionEligible)
    }

    @Test
    fun arrivedDeadline_allowsInterventionForUnfinishedWork_evenWithoutReliableHistory() {
        val todayTarget = deriveDeadline(
            estimateMinutes = 300,
            targetDate = today,
            profile = PersonalPaceProfile.empty(),
        )
        val passedTarget = deriveDeadline(
            estimateMinutes = 300,
            targetDate = today.minusDays(2),
            profile = PersonalPaceProfile.empty(),
        )

        assertTrue(todayTarget.deadlinePassed)
        assertTrue(todayTarget.deadlineInterventionEligible)
        assertTrue(passedTarget.deadlineInterventionEligible)
    }

    @Test
    fun emptyAndCompletedDeadlines_neverExposeDeadlineAction() {
        val empty = CollectionSummary.derive(
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionSort.NAME,
            targetDate = today,
            members = emptyList(),
            today = today,
            personalPace = reliableProfile,
        )
        val complete = deriveDeadline(
            estimateMinutes = 100,
            targetDate = today,
            playtimeMinutes = 100,
        )

        assertTrue(empty.empty)
        assertFalse(empty.deadlineInterventionEligible)
        assertFalse(complete.deadlineInterventionEligible)
        assertEquals(0, complete.remainingMinutes)
    }

    @Test
    fun completionGoalAndQueueUseCompletionistHorizons_basicListHasNone() {
        val completion = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionSort.COMPLETION_FRACTION,
            targetDate = null,
            members = listOf(member(appId = 1, completionistMinutes = 100)),
            today = today,
            personalPace = reliableProfile,
        )
        val queue = CollectionSummary.derive(
            mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionSort.MANUAL_SEQUENCE,
            targetDate = null,
            members = listOf(
                member(appId = 1, name = "Done", completionistMinutes = 100, playtimeMinutes = 100),
                member(appId = 2, name = "Next", completionistMinutes = 120),
            ),
            today = today,
            personalPace = reliableProfile,
        )
        val basic = CollectionSummary.derive(
            mode = CollectionMode.BASIC,
            sort = CollectionSort.NAME,
            targetDate = null,
            members = listOf(member(appId = 1, completionistMinutes = 100)),
            today = today,
            personalPace = reliableProfile,
        )

        assertNotNull(completion.completionHorizonDate)
        assertEquals("Next", queue.nextUp?.name)
        assertNotNull(queue.nextGameHorizonDate)
        assertNotNull(queue.queueHorizonDate)
        assertNull(basic.pacingState)
        assertNull(basic.completionHorizonDate)
        assertNull(basic.paceConfidence)
    }

    @Test
    fun queueCanForecastNextKnownGame_withoutClaimingWholeQueueWhenLaterEstimateIsMissing() {
        val queue = CollectionSummary.derive(
            mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionSort.MANUAL_SEQUENCE,
            targetDate = null,
            members = listOf(
                member(appId = 1, name = "Next", completionistMinutes = 120),
                member(appId = 2, name = "Unknown", completionistMinutes = null),
            ),
            today = today,
            personalPace = reliableProfile,
        )

        assertEquals(CollectionPacingState.INCOMPLETE_DATA, queue.pacingState)
        assertNotNull(queue.nextGameHorizonDate)
        assertNull(queue.queueHorizonDate)
    }

    private fun deriveDeadline(
        estimateMinutes: Int,
        targetDate: LocalDate = today.plusDays(2),
        profile: PersonalPaceProfile = reliableProfile,
        memberEstimateMinutes: Int? = estimateMinutes,
        playtimeMinutes: Int = 0,
    ): CollectionBanner = CollectionSummary.derive(
        mode = CollectionMode.DEADLINE_GOAL,
        sort = CollectionSort.DAYS_REMAINING,
        targetDate = targetDate,
        members = listOf(
            member(
                appId = 1,
                playtimeMinutes = playtimeMinutes,
                completionistMinutes = memberEstimateMinutes,
                mainStoryMinutes = memberEstimateMinutes,
            ),
        ),
        today = today,
        timeBasis = CollectionTimeBasis.MAIN_STORY,
        personalPace = profile,
    )

    private fun member(
        appId: Long,
        name: String = "Game $appId",
        playtimeMinutes: Int = 0,
        completionistMinutes: Int? = null,
        mainStoryMinutes: Int? = null,
        manualDone: Boolean = false,
    ) = CollectionMemberSignals(
        appId = appId,
        name = name,
        playtimeMinutes = playtimeMinutes,
        completionistMinutes = completionistMinutes,
        mainStoryMinutes = mainStoryMinutes,
        mainExtraMinutes = null,
        allStylesMinutes = null,
        achievementsUnlocked = null,
        achievementsTotal = null,
        manualDone = manualDone,
    )
}
