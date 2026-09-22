package com.example.backlogium.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HistorySectionsTest {

    private fun day(date: String) = HistoryDayGroup(
        date = date,
        minutesPlayed = 0,
        goalMinutesPlayed = 0,
        questMet = false,
        games = emptyList(),
        achievements = HistoryAchievements(emptyList(), 0),
    )

    @Test
    fun todayPresent_isSeparatedFromEarlierHistoryWithoutReordering() {
        val sections = historySections(
            listOf(day("2026-09-21"), day("2026-09-20"), day("2026-09-19")),
            today = "2026-09-21",
        )

        assertEquals("2026-09-21", sections.today?.date)
        assertEquals(listOf("2026-09-20", "2026-09-19"), sections.earlier.map { it.date })
    }

    @Test
    fun todayAbsent_keepsAllRowsInEarlierHistory() {
        val sections = historySections(
            listOf(day("2026-09-20"), day("2026-09-19")),
            today = "2026-09-21",
        )

        assertNull(sections.today)
        assertEquals(listOf("2026-09-20", "2026-09-19"), sections.earlier.map { it.date })
    }

    @Test
    fun progressOnlyDay_isStillPresentedInEarlierHistory() {
        val progressOnly = day("2026-09-20")

        val sections = historySections(listOf(progressOnly), today = "2026-09-21")

        assertEquals(listOf(progressOnly), sections.earlier)
    }

    @Test
    fun nonUsLocaleFormatsTheDateWithoutChangingItsLocalDateKey() {
        val english = formatHistoryDate("2026-09-21", Locale.US)
        val german = formatHistoryDate("2026-09-21", Locale.GERMANY)

        assertTrue(english != german)
        assertTrue(german.contains("2026"))
    }
}
