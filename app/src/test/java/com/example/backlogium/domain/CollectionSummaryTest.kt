package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Plain-JVM tests for the pure collection-summary derivation (tasks 2.3–2.7). Covers every
 * scenario in the "Collection summary derivation," "Ordered-queue sequencing," and
 * "Collection member ordering" requirements — no Android deps, no clocks.
 */
class CollectionSummaryTest {

    private val today: LocalDate = LocalDate.parse("2026-08-04")

    private fun member(
        appId: Long = 1L,
        name: String? = "Game $appId",
        playtimeMinutes: Int = 0,
        completionistMinutes: Int? = null,
        achievementsUnlocked: Int? = null,
        achievementsTotal: Int? = null,
    ) = CollectionMemberSignals(
        appId = appId,
        name = name,
        playtimeMinutes = playtimeMinutes,
        completionistMinutes = completionistMinutes,
        achievementsUnlocked = achievementsUnlocked,
        achievementsTotal = achievementsTotal,
    )

    // --- Collection summary derivation ---

    @Test
    fun basicMode_bannerIsMemberCountOnly() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.BASIC,
            sort = CollectionMode.BASIC.defaultSort(),
            targetDate = null,
            members = listOf(member(1), member(2)),
            today = today,
        )
        assertEquals(2, banner.memberCount)
        assertEquals("2 games", banner.memberCountLabel)
        assertNull(banner.completionFraction)
        assertEquals(0, banner.achievementsRemaining)
        assertNull(banner.daysRemaining)
        assertNull(banner.nextUp)
        assertFalse(banner.empty)
    }

    @Test
    fun completionProgress_withHltbData_aggregatesMeanOfFractions() {
        // 30/60 → 0.5 and 90/100 → 0.9; mean = 0.7
        val banner = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionMode.COMPLETION_GOAL.defaultSort(),
            targetDate = null,
            members = listOf(
                member(appId = 1, playtimeMinutes = 30, completionistMinutes = 60),
                member(appId = 2, playtimeMinutes = 90, completionistMinutes = 100),
            ),
            today = today,
        )
        assertEquals(0.7, banner.completionFraction!!, 1e-9)
    }

    @Test
    fun memberWithoutHltbData_isExcludedFromTheAggregateFraction() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionMode.COMPLETION_GOAL.defaultSort(),
            targetDate = null,
            members = listOf(
                member(appId = 1, playtimeMinutes = 30, completionistMinutes = 60), // 0.5
                member(appId = 2, playtimeMinutes = 999, completionistMinutes = null), // excluded
            ),
            today = today,
        )
        assertEquals(0.5, banner.completionFraction!!, 1e-9)
        assertEquals(2, banner.memberCount) // still a member; just contributes no fraction
    }

    @Test
    fun noMemberWithHltbData_yieldsNullAggregateFraction() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionMode.COMPLETION_GOAL.defaultSort(),
            targetDate = null,
            members = listOf(member(1), member(2)),
            today = today,
        )
        assertNull(banner.completionFraction)
    }

    @Test
    fun achievementsRemaining_sumsLockedAcrossMembers() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionMode.COMPLETION_GOAL.defaultSort(),
            targetDate = null,
            members = listOf(
                member(appId = 1, achievementsUnlocked = 7, achievementsTotal = 10),
                member(appId = 2, achievementsUnlocked = 0, achievementsTotal = 5),
            ),
            today = today,
        )
        assertEquals(8, banner.achievementsRemaining)
    }

    @Test
    fun memberWithoutAchievementData_contributesZeroAndDoesNotFail() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionMode.COMPLETION_GOAL.defaultSort(),
            targetDate = null,
            members = listOf(
                member(appId = 1, achievementsUnlocked = 2, achievementsTotal = 4),
                member(appId = 2), // no achievement data at all
            ),
            today = today,
        )
        assertEquals(2, banner.achievementsRemaining)
    }

    @Test
    fun deadlineCountdown_isDaysFromInjectedToday() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionMode.DEADLINE_GOAL.defaultSort(),
            targetDate = LocalDate.parse("2026-08-16"),
            members = listOf(member(1, playtimeMinutes = 30, completionistMinutes = 60)),
            today = today,
        )
        assertEquals(12L, banner.daysRemaining)
        assertFalse(banner.deadlinePassed)
    }

    @Test
    fun deadlinePassed_whenTargetOnOrBeforeToday_notNegativeCountdown() {
        val onToday = CollectionSummary.derive(
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionMode.DEADLINE_GOAL.defaultSort(),
            targetDate = today,
            members = listOf(member(1)),
            today = today,
        )
        val before = CollectionSummary.derive(
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionMode.DEADLINE_GOAL.defaultSort(),
            targetDate = today.minusDays(3),
            members = listOf(member(1)),
            today = today,
        )
        assertTrue(onToday.deadlinePassed)
        assertTrue(before.deadlinePassed)
        assertTrue(before.daysRemaining!! <= 0)
    }

    @Test
    fun deadlineModeWithoutTargetDate_hasNoCountdown() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionMode.DEADLINE_GOAL.defaultSort(),
            targetDate = null,
            members = listOf(member(1)),
            today = today,
        )
        assertNull(banner.daysRemaining)
        assertFalse(banner.deadlinePassed)
    }

    @Test
    fun nonDeadlineMode_neverCarriesCountdownEvenWithTargetDateStored() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.BASIC,
            sort = CollectionMode.BASIC.defaultSort(),
            targetDate = LocalDate.parse("2026-08-16"),
            members = listOf(member(1)),
            today = today,
        )
        assertNull(banner.daysRemaining)
    }

    @Test
    fun emptyCollection_presentsEmptyBannerWithoutDerivedValues() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionMode.COMPLETION_GOAL.defaultSort(),
            targetDate = null,
            members = emptyList(),
            today = today,
        )
        assertTrue(banner.empty)
        assertEquals(0, banner.memberCount)
        assertNull(banner.completionFraction)
        assertEquals(0, banner.achievementsRemaining)
        assertNull(banner.nextUp)
        assertFalse(banner.queueCompleted)
    }

    @Test
    fun absentGameRow_memberIsOmittedFromSummaryWithoutFailing() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionMode.COMPLETION_GOAL.defaultSort(),
            targetDate = null,
            members = listOf(
                member(appId = 1, playtimeMinutes = 30, completionistMinutes = 60),
                member(appId = 2, name = null, playtimeMinutes = 999, completionistMinutes = 60),
            ),
            today = today,
        )
        assertEquals(1, banner.memberCount)
        assertEquals(0.5, banner.completionFraction!!, 1e-9)
    }

    // --- Ordered-queue sequencing ---

    @Test
    fun orderedQueue_nextGameIsFirstInSequence() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionMode.ORDERED_QUEUE.defaultSort(),
            targetDate = null,
            members = listOf(member(3, "Third"), member(1, "First"), member(2, "Second")),
            today = today,
        )
        assertEquals("Third", banner.nextUp?.name)
        assertEquals(1, banner.nextUpPosition)
        assertFalse(banner.queueCompleted)
    }

    @Test
    fun orderedQueue_reorderingMembers_changesNextGame() {
        val original = listOf(member(1, "First"), member(2, "Second"))
        val reordered = original.reversed()
        val before = CollectionSummary.derive(
            CollectionMode.ORDERED_QUEUE, CollectionMode.ORDERED_QUEUE.defaultSort(), null, original, today,
        )
        val after = CollectionSummary.derive(
            CollectionMode.ORDERED_QUEUE, CollectionMode.ORDERED_QUEUE.defaultSort(), null, reordered, today,
        )
        assertEquals("First", before.nextUp?.name)
        assertEquals("Second", after.nextUp?.name)
    }

    @Test
    fun orderedQueue_queueCompleted_whenEveryMemberFullyComplete() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionMode.ORDERED_QUEUE.defaultSort(),
            targetDate = null,
            members = listOf(
                member(1, playtimeMinutes = 60, completionistMinutes = 60),
                member(2, playtimeMinutes = 120, completionistMinutes = 100),
            ),
            today = today,
        )
        assertTrue(banner.queueCompleted)
    }

    @Test
    fun orderedQueue_withIncompleteMembers_isNotCompleted() {
        val banner = CollectionSummary.derive(
            mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionMode.ORDERED_QUEUE.defaultSort(),
            targetDate = null,
            members = listOf(
                member(1, playtimeMinutes = 30, completionistMinutes = 60),
                member(2, playtimeMinutes = 120, completionistMinutes = 100),
            ),
            today = today,
        )
        assertFalse(banner.queueCompleted)
        assertEquals("Game 1", banner.nextUp?.name)
    }

    @Test
    fun nonQueueModes_ignoreSequenceOrder() {
        val members = listOf(member(2, "Bravo"), member(1, "Alpha"), member(3, "Charlie"))
        val byName = CollectionSummary.order(CollectionMode.BASIC, CollectionSort.NAME, members)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), byName.map { it.name })
        assertNotEquals(listOf("Bravo", "Alpha", "Charlie"), byName.map { it.name })
    }

    // --- Collection member ordering ---

    @Test
    fun sortingByName_ordersAlphabeticallyCaseInsensitive() {
        val members = listOf(member(2, "beta"), member(1, "Alpha"))
        val ordered = CollectionSummary.order(CollectionMode.BASIC, CollectionSort.NAME, members)
        assertEquals(listOf("Alpha", "beta"), ordered.map { it.name })
    }

    @Test
    fun sortingByCompletionFraction_highestFirst() {
        val members = listOf(
            member(1, "Half", playtimeMinutes = 30, completionistMinutes = 60),
            member(2, "Done", playtimeMinutes = 100, completionistMinutes = 100),
            member(3, "NoHltb", playtimeMinutes = 10),
        )
        val ordered = CollectionSummary.order(
            CollectionMode.COMPLETION_GOAL,
            CollectionSort.COMPLETION_FRACTION,
            members,
        )
        assertEquals(listOf("Done", "Half", "NoHltb"), ordered.map { it.name })
    }

    @Test
    fun orderedQueue_usesManualOrderRegardlessOfSortSelection() {
        val members = listOf(member(2, "Zulu"), member(1, "Alpha"))
        val ordered = CollectionSummary.order(
            CollectionMode.ORDERED_QUEUE,
            CollectionSort.NAME, // ignored in queue mode
            members,
        )
        assertEquals(listOf("Zulu", "Alpha"), ordered.map { it.name })
    }

    @Test
    fun defaultSortPerMode_matchesSensibleOrder() {
        assertEquals(CollectionSort.NAME, CollectionMode.BASIC.defaultSort())
        assertEquals(CollectionSort.COMPLETION_FRACTION, CollectionMode.COMPLETION_GOAL.defaultSort())
        assertEquals(CollectionSort.DAYS_REMAINING, CollectionMode.DEADLINE_GOAL.defaultSort())
        assertEquals(CollectionSort.MANUAL_SEQUENCE, CollectionMode.ORDERED_QUEUE.defaultSort())
    }

    @Test
    fun parseToleratesUnknownStoredSortName() {
        assertEquals(CollectionSort.NAME, collectionSortOrNull("NAME"))
        assertNull(collectionSortOrNull("SOME_REMOVED_SORT"))
        assertNull(collectionSortOrNull(null))
    }
}

private fun assertNotEquals(unexpected: Any?, actual: Any?) {
    assertTrue("Expected not equal: $unexpected vs $actual", unexpected != actual)
}
