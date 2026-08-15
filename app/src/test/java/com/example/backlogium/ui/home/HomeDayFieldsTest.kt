package com.example.backlogium.ui.home

import com.example.backlogium.data.repo.DayProgress
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Home's per-day fields, which follow the date they are given
 * (auditfix-day-attribution Decision 6).
 *
 * The defect these cover was not in this resolution but in its input: Home called `today()` inside
 * a combine over data flows, so after midnight it passed the previous date and faithfully returned
 * the previous day's row. Pairing these with `CurrentDateProviderTest` covers both halves — the
 * date advances at the boundary, and the fields follow the date.
 */
class HomeDayFieldsTest {

    private val aug14 = LocalDate.of(2026, 8, 14)
    private val aug15 = LocalDate.of(2026, 8, 15)

    private val days = listOf(
        DayProgress(date = "2026-08-14", minutesPlayed = 21, goalMinutesPlayed = 21, questMet = true),
        DayProgress(date = "2026-08-13", minutesPlayed = 95, goalMinutesPlayed = 0, questMet = true),
    )

    @Test
    fun `resolves the stored row for the given date`() {
        val fields = homeDayFields(days, aug14)

        assertEquals(21, fields.minutesPlayed)
        assertEquals(true, fields.questMet)
    }

    @Test
    fun `a date with no stored row reads as zero and unmet`() {
        // The new day, before any sync has written its row. Reporting the previous day's 21 minutes
        // and its satisfied quest here is the reported bug.
        val fields = homeDayFields(days, aug15)

        assertEquals(0, fields.minutesPlayed)
        assertFalse(fields.questMet)
    }

    @Test
    fun `never falls back to the nearest row`() {
        val fields = homeDayFields(days, LocalDate.of(2026, 8, 20))

        assertEquals(0, fields.minutesPlayed)
        assertFalse(fields.questMet)
    }

    @Test
    fun `an empty history reads as zero and unmet`() {
        val fields = homeDayFields(emptyList(), aug14)

        assertEquals(0, fields.minutesPlayed)
        assertFalse(fields.questMet)
    }
}
