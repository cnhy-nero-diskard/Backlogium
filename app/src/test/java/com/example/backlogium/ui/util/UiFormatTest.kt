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
 * [UiFormat.timeOfDay] and [UiFormat.approxTime] (regroup-history tasks 4.1-4.3).
 *
 * `approxTime` deliberately formats a single instant, not a start–end range: an earlier version
 * showed both a session's start and end time, which real users read as "subtract these for the
 * duration" — and that duration can legitimately disagree with the session's tracked minutes once
 * Steam's own playtime counter lags, making the screen look arithmetically wrong. Anchoring on one
 * approximate instant removes the two-endpoint shape that invites that reflex.
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
    fun approxTime_marksAnInstantAsApproximate() {
        val formatted = UiFormat.approxTime(at(2026, 7, 25, 15, 0), zone)

        assertEquals("~3:00 PM", formatted.normalizeSpaces())
    }

    @Test
    fun approxTime_carriesNoEndpoint_justOneInstant() {
        // No "–", no second clock time — nothing here for a reader to subtract into a duration.
        val formatted = UiFormat.approxTime(at(2026, 7, 25, 15, 0), zone)

        assertTrue(!formatted.contains("–"))
    }

    @Test
    fun approxTime_acrossMidnight_formatsOnItsOwnClockFace() {
        val justBeforeMidnight = UiFormat.approxTime(at(2026, 7, 25, 23, 50), zone)
        val justAfterMidnight = UiFormat.approxTime(at(2026, 7, 26, 0, 20), zone)

        assertEquals("~11:50 PM", justBeforeMidnight.normalizeSpaces())
        assertEquals("~12:20 AM", justAfterMidnight.normalizeSpaces())
    }

    @Test
    fun timeOfDay_carriesNoDatePart() {
        val formatted = UiFormat.timeOfDay(at(2026, 7, 25, 9, 5), zone)

        assertEquals("9:05 AM", formatted.normalizeSpaces())
        assertTrue("no year/month/day in a time-of-day string", !formatted.contains("2026"))
    }

    @Test
    fun liveElapsed_underAMinute_countsSeconds() {
        // The case `minutes()` got wrong: a just-detected session must not read as a static "0m".
        assertEquals("0s", UiFormat.liveElapsed(0L))
        assertEquals("7s", UiFormat.liveElapsed(7_000L))
        assertEquals("59s", UiFormat.liveElapsed(59_999L))
    }

    @Test
    fun liveElapsed_underAnHour_showsMinutesAndSeconds() {
        assertEquals("1m 0s", UiFormat.liveElapsed(60_000L))
        assertEquals("5m 23s", UiFormat.liveElapsed(323_000L))
    }

    @Test
    fun liveElapsed_pastAnHour_dropsSeconds() {
        // Past an hour a ticking seconds digit is noise, not liveness.
        assertEquals("1h 0m", UiFormat.liveElapsed(3_600_000L))
        assertEquals("2h 35m", UiFormat.liveElapsed(9_300_000L))
    }

    @Test
    fun liveElapsed_negativeElapsed_clampsToZero() {
        // A clock adjustment can put the persisted start time in the future; never render "-1s".
        assertEquals("0s", UiFormat.liveElapsed(-5_000L))
    }

    @Test
    fun compactCount_leavesSmallNumbersAlone() {
        // "842" is already as short as it gets; abbreviating it would only lose precision.
        assertEquals("0", UiFormat.compactCount(0))
        assertEquals("842", UiFormat.compactCount(842))
        assertEquals("999", UiFormat.compactCount(999))
    }

    @Test
    fun compactCount_usesOneDecimalOnlyBelowTenOfAUnit() {
        assertEquals("1K", UiFormat.compactCount(1_000))
        assertEquals("1.5K", UiFormat.compactCount(1_500))
        // Past ten of a unit the second digit stops being easier to read than the number it
        // replaced, so it is dropped.
        assertEquals("23K", UiFormat.compactCount(22_788))
        assertEquals("677K", UiFormat.compactCount(676_762))
        assertEquals("1.1M", UiFormat.compactCount(1_130_216))
        assertEquals("9.9M", UiFormat.compactCount(9_850_631))
        assertEquals("12M", UiFormat.compactCount(12_400_000))
    }

    @Test
    fun compactCount_choosesTheUnitAfterRounding() {
        // The trap: 999,950 divided by a raw 1,000,000 threshold stays in thousands and renders
        // "1000K", which is longer than the figure it was meant to shorten.
        assertEquals("1M", UiFormat.compactCount(999_950))
        assertEquals("1M", UiFormat.compactCount(1_000_000))
        // And just below the rounding point it stays in thousands.
        assertEquals("999K", UiFormat.compactCount(999_400))
        // The opposite trap: comparing rounded *millions* instead would switch here and turn a
        // perfectly good "950K" into a less precise, no-shorter "1M".
        assertEquals("950K", UiFormat.compactCount(950_000))
    }

    @Test
    fun compactCount_clampsNegativesRatherThanRenderingThem() {
        // A count is never negative; if one arrives, "-1.2K players" is worse than "0".
        assertEquals("0", UiFormat.compactCount(-1_200))
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
