package com.example.backlogium.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DailyActivityTest {
    private val date = LocalDate.of(2026, 10, 3)
    private fun read(minutes: List<Int>, credit: Long?) = dailyActivity(
        date, "fixture-account", minutes.map { DailyActivityEvidence(1, "Game", it, true) }, credit,
        credit?.let { it >= 30 },
    )

    @Test fun preservesBothScopesAndSignedDifference() {
        assertEquals(0L, read(listOf(20, 25), 45).differenceMinutes)
        assertEquals(20L, read(listOf(40), 60).differenceMinutes)
        assertEquals(-20L, read(listOf(50), 30).differenceMinutes)
        assertEquals(50L, read(listOf(50), 30).recordedMinutes)
    }

    @Test fun absentCreditDoesNotEvaluateQuest() {
        val result = read(listOf(60), null)
        assertNull(result.creditedMinutes)
        assertNull(result.questMet)
        assertNull(result.differenceMinutes)
    }

    @Test fun progressOnlyAndRecordedZeroRemainDistinct() {
        assertTrue(read(emptyList(), 60).games.isEmpty())
        assertEquals(60L, read(emptyList(), 60).differenceMinutes)
        assertEquals(1, read(listOf(0), 0).games.size)
        assertNull(read(emptyList(), null).creditedMinutes)
    }

    @Test fun widerSumsAndMissingIdentityDoNotDisappear() {
        val result = dailyActivity(date, "fixture-account", listOf(
            DailyActivityEvidence(1, null, Int.MAX_VALUE, false),
            DailyActivityEvidence(1, null, Int.MAX_VALUE, false),
            DailyActivityEvidence(2, "Other", 40, true),
        ), 30, false)
        assertEquals(4_294_967_334L, result.recordedMinutes)
        assertEquals(1L, result.games.first().appId)
        assertNull(result.games.first().name)
        assertFalse(result.games.first().detailAvailable)
        assertFalse(result.questMet!!)
    }
}
