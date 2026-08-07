package com.example.backlogium.ui.collections

import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionPacingState
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPacingPresentationTest {

    @Test
    fun stateLabels_coverEveryPacingState() {
        assertEquals("On track", collectionPacingStateLabel(CollectionPacingState.ON_TRACK))
        assertEquals("At risk", collectionPacingStateLabel(CollectionPacingState.AT_RISK))
        assertEquals("Learning", collectionPacingStateLabel(CollectionPacingState.LEARNING))
        assertEquals("Incomplete", collectionPacingStateLabel(CollectionPacingState.INCOMPLETE_DATA))
        assertEquals(null, collectionPacingStateLabel(null))
    }

    @Test
    fun pacingSection_isShownForGoalModes_butNotBasicLists() {
        assertFalse(collectionModePacingSectionVisible(CollectionMode.BASIC))
        assertTrue(collectionModePacingSectionVisible(CollectionMode.COMPLETION_GOAL))
        assertTrue(collectionModePacingSectionVisible(CollectionMode.DEADLINE_GOAL))
        assertTrue(collectionModePacingSectionVisible(CollectionMode.ORDERED_QUEUE))
    }

    @Test
    fun deadlineAction_usesOnlyTheDomainEligibilityFlag() {
        val base = CollectionSummary.derive(
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionSort.NAME,
            targetDate = LocalDate.of(2026, 8, 9),
            members = emptyList(),
            today = LocalDate.of(2026, 8, 7),
        )

        assertTrue(collectionDeadlineActionVisible(base.copy(deadlineInterventionEligible = true)))
        assertFalse(collectionDeadlineActionVisible(base.copy(deadlineInterventionEligible = false)))
    }

    @Test
    fun learningFallback_roundsUpActivePlayDays_withoutClaimingCalendarFit() {
        assertEquals(34, learningDeadlineActiveDaysNeeded(5_064, 153.0))
        assertEquals(1, learningDeadlineActiveDaysNeeded(153, 153.0))
        assertNull(learningDeadlineActiveDaysNeeded(null, 153.0))
        assertNull(learningDeadlineActiveDaysNeeded(300, null))
        assertNull(learningDeadlineActiveDaysNeeded(300, 0.0))
    }

    @Test
    fun deadlineUrgency_marksUpcomingAndDueDates() {
        assertEquals(DeadlineUrgency.NORMAL, deadlineUrgency(null))
        assertEquals(DeadlineUrgency.NORMAL, deadlineUrgency(8))
        assertEquals(DeadlineUrgency.SOON, deadlineUrgency(7))
        assertEquals(DeadlineUrgency.SOON, deadlineUrgency(1))
        assertEquals(DeadlineUrgency.DUE_OR_PAST, deadlineUrgency(0))
        assertEquals(DeadlineUrgency.DUE_OR_PAST, deadlineUrgency(-1))
    }
}
