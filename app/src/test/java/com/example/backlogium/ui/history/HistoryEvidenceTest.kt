package com.example.backlogium.ui.history

import com.example.backlogium.data.repo.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HistoryEvidenceTest {
    private val date = LocalDate.of(2026, 10, 3)
    private val zone = ZoneId.of("UTC")
    private val at = date.atStartOfDay(zone).toInstant().toEpochMilli()
    @Test fun creditNeverRescalesGamesAndMissingOutcomeIsUnknown() {
        val sessions = listOf(PlaySession(1, 9, at, 40, false))
        for (credit in listOf(60, 20)) {
            val day = groupHistory(sessions, emptyList(), listOf(DayProgress(date.toString(), credit, 0, true)), emptyList(), zone).single()
            assertEquals(40L, day.activity!!.recordedMinutes)
            assertEquals(credit - 40L, day.activity.differenceMinutes)
            assertTrue(day.questMet)
            assertFalse(day.games.single().detailAvailable)
        }
        assertNull(groupHistory(sessions, emptyList(), emptyList(), emptyList(), zone).single().activity!!.questMet)
    }
    @Test fun progressOnlyRetainsCreditWithoutSyntheticGamesAndWideTotalsDoNotWrap() {
        val day = groupHistory(emptyList(), emptyList(), listOf(DayProgress(date.toString(), 60, 0, true)), emptyList(), zone).single()
        assertEquals(60L, day.activity!!.creditedMinutes)
        assertTrue(day.games.isEmpty())
        val large = groupHistory(listOf(PlaySession(1, 9, at, Int.MAX_VALUE, false),
            PlaySession(2, 9, at + 1, Int.MAX_VALUE, true)), emptyList(), emptyList(), emptyList(), zone).single()
        assertEquals(2L * Int.MAX_VALUE, large.activity!!.recordedMinutes)
        assertEquals(2L * Int.MAX_VALUE, large.games.single().recordedMinutes)
    }
}
