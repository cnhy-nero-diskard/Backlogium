package com.example.backlogium.data.repo

import com.example.backlogium.data.local.entity.HltbMatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HltbMatchStateTest {

    @Test
    fun missingHltbRowIsNotCovered_notNoMatch() {
        val missingRow: HltbMatchStatus? = null

        assertEquals(HltbMatchState.NOT_COVERED, missingRow.toDomain())
        assertNotEquals(HltbMatchState.UNMATCHED, missingRow.toDomain())
    }

    @Test
    fun everyCompletedLookupOutcomeClearsNotCovered() {
        val completedOutcomes = HltbMatchStatus.entries.map { it.toDomain() }

        assertEquals(
            listOf(
                HltbMatchState.RESOLVED,
                HltbMatchState.NEEDS_REVIEW,
                HltbMatchState.UNMATCHED,
            ),
            completedOutcomes,
        )
        completedOutcomes.forEach { assertNotEquals(HltbMatchState.NOT_COVERED, it) }
    }
}
