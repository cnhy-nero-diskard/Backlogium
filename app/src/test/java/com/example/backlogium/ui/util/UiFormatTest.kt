package com.example.backlogium.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * [UiFormat.timeOfDay] and [UiFormat.sessionRange] (regroup-history tasks 4.1-4.3): the
 * open-session form and a range crossing midnight, which is where an off-by-one in the
 * open/closed branch would show up first.
 *
 * Locale is pinned to US for the run — [UiFormat] takes no locale parameter, so leaving the JVM
 * default unset would make the exact "AM"/"PM" text CI-machine-dependent.
 */
class UiFormatTest {

    private val zone = ZoneId.of("UTC")
    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun closedSession_rangeShowsBothEndpointsWithApproximationMarker() {
        val start = at(2026, 7, 25, 15, 0)
        val end = at(2026, 7, 25, 17, 55)

        val range = UiFormat.sessionRange(startAt = start, endAt = end, open = false, zone = zone)

        assertEquals("~3:00 PM – 5:55 PM", range.normalizeSpaces())
    }

    @Test
    fun openSession_rangeIsOpenEnded() {
        val start = at(2026, 7, 25, 15, 0)

        val range = UiFormat.sessionRange(startAt = start, endAt = null, open = true, zone = zone)

        assertEquals("~3:00 PM – now", range.normalizeSpaces())
    }

    @Test
    fun openSession_ignoresAStaleEndAt() {
        // open=true is the authority; a lingering endAt from a prior extend must not surface.
        val start = at(2026, 7, 25, 15, 0)
        val staleEnd = at(2026, 7, 25, 15, 10)

        val range = UiFormat.sessionRange(startAt = start, endAt = staleEnd, open = true, zone = zone)

        assertEquals("~3:00 PM – now", range.normalizeSpaces())
    }

    @Test
    fun rangeCrossingMidnight_formatsBothEndpointsOnTheirOwnClockFace() {
        val start = at(2026, 7, 25, 23, 50)
        val end = at(2026, 7, 26, 0, 20)

        val range = UiFormat.sessionRange(startAt = start, endAt = end, open = false, zone = zone)

        assertEquals("~11:50 PM – 12:20 AM", range.normalizeSpaces())
    }

    @Test
    fun timeOfDay_carriesNoDatePart() {
        val formatted = UiFormat.timeOfDay(at(2026, 7, 25, 9, 5), zone)

        assertEquals("9:05 AM", formatted.normalizeSpaces())
        assertTrue("no year/month/day in a time-of-day string", !formatted.contains("2026"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /**
     * Newer JDKs format the AM/PM marker using a Unicode narrow no-break space rather than a
     * plain ASCII one, per updated CLDR data — a JDK-version artifact, not something [UiFormat]
     * should chase. The Unicode "space separator" category (`\p{Zs}`) covers that and every other
     * space variant, so this normalization doesn't itself depend on knowing which one the host
     * JDK picked.
     */
    private fun String.normalizeSpaces(): String = Regex("\\p{Zs}").replace(this, " ")
}
