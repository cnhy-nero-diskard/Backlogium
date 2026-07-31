package com.example.backlogium.ui.util

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Small presentation helpers shared across screens. */
object UiFormat {

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    private val timeOfDayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

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

    /** Format an epoch-millis timestamp in the device's local zone, or "—" when unset. */
    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return "—"
        return Instant.ofEpochMilli(epochMillis)
            .atZone(zone)
            .format(dateTimeFormatter)
    }

    /** Locale-aware time of day with no date part, e.g. "3:00 PM". */
    fun timeOfDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(timeOfDayFormatter)

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
}
