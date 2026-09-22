package com.example.backlogium.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.backlogium.R
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Small presentation helpers shared across screens. */
object UiFormat {

    /** Locale-aware duration text for user-visible activity summaries. */
    @Composable
    fun localizedMinutes(minutes: Int): String {
        val safe = minutes.coerceAtLeast(0)
        val hours = safe / 60
        val remainder = safe % 60
        if (hours == 0) {
            return pluralStringResource(R.plurals.duration_minutes, safe, safe)
        }
        val hourText = pluralStringResource(R.plurals.duration_hours, hours, hours)
        if (remainder == 0) return hourText
        val minuteText = pluralStringResource(R.plurals.duration_minutes, remainder, remainder)
        return stringResource(R.string.duration_hours_and_minutes, hourText, minuteText)
    }

    private fun dateTimeFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())

    private fun timeOfDayFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())

    private fun dateFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())

    /** "1h 20m", "45m", or "0m". */
    fun minutes(minutes: Int): String {
        val safe = minutes.coerceAtLeast(0)
        val hours = safe / 60
        val mins = safe % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }
    }

    /**
     * A *live*, second-ticking duration for the Home now-playing card — distinct from [minutes],
     * which formats a settled total and would render a just-started session as a static "0m".
     *
     * Seconds are shown below the hour mark precisely because this value updates every second:
     * a visibly advancing number is what makes the card read as live. Past an hour they are
     * dropped — by then the minute is the meaningful unit and a ticking seconds digit is noise.
     */
    fun liveElapsed(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** Format an epoch-millis timestamp in the device's local zone, or "—" when unset. */
    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return "—"
        return Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .format(dateTimeFormatter())
    }

    /**
     * Locale-aware date with no time part, e.g. "Aug 30, 2026" — for a fact whose day is what
     * matters and whose hour would only imply a precision it does not have.
     */
    fun date(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateFormatter())

    /** Locale-aware date for calendar-only earned events. */
    fun date(date: LocalDate): String = date.format(dateFormatter())

    /** Locale-aware time of day with no date part, e.g. "3:00 PM". */
    fun timeOfDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(timeOfDayFormatter())

    /**
     * An approximate instant, e.g. `"~3:00 PM"` — not a range. A session's start and its tracked
     * minutes are two different measurements (see `SessionDiffer`) that can legitimately disagree
     * once Steam's own playtime counter lags; showing them as a start–end range invites subtracting
     * the two into a "duration" that then looks arithmetically wrong the moment they diverge.
     * Anchoring on a single approximate instant sidesteps that reflex instead of trying to caveat
     * it away with wording.
     */
    fun approxTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "~${timeOfDay(epochMillis, zone)}"

    /** Locale-grouped integer, e.g. "1,206,380" — for counts large enough that digit-grouping matters. */
    fun count(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    /** Locale-grouped long for XP and other profile totals. */
    fun count(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    /** Locale-aware whole percentage for compact progress banners. */
    fun percent(fraction: Double): String =
        NumberFormat.getPercentInstance().format(fraction.coerceIn(0.0, 1.0))

    /**
     * Abbreviated integer, e.g. "677K" or "1.1M" — for counts that sit beside other values in a
     * dense row, where the exact digits carry no decision and the width does.
     *
     * One decimal place only below ten of a unit ("1.1M", but "12M"), because the second digit is
     * where an abbreviation stops being easier to read than the number it replaced. Anything under
     * a thousand is left alone: "842" is already as short as it gets.
     */
    fun compactCount(value: Int): String {
        val safe = value.coerceAtLeast(0)
        if (safe < 1_000) return safe.toString()
        // The unit is chosen against what the smaller one would actually print, not against a raw
        // threshold: rounding can push a value into the next unit, and 999,950 in thousands rounds
        // to 1,000 — rendering "1000K", which is longer than the figure it was meant to shorten.
        // Comparing the rounded *millions* instead would switch at 950,000 and turn a perfectly
        // good "950K" into "1M".
        val thousands = safe / 1_000.0
        if (safe < 1_000_000 && roundToTenth(thousands) < 1_000.0) {
            return abbreviate(thousands, "K")
        }
        return abbreviate(safe / 1_000_000.0, "M")
    }

    private fun abbreviate(scaled: Double, suffix: String): String {
        val rounded = roundToTenth(scaled)
        return if (rounded < 10.0 && rounded != kotlin.math.floor(rounded)) {
            "$rounded$suffix"
        } else {
            "${Math.round(rounded)}$suffix"
        }
    }

    private fun roundToTenth(value: Double): Double = Math.round(value * 10.0) / 10.0
}
