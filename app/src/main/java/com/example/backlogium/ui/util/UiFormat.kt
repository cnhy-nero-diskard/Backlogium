package com.example.backlogium.ui.util

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
     * An approximate session range, e.g. `"~3:00 PM – 5:55 PM"`, or `"~3:00 PM – now"` while the
     * session is still open. The leading tilde marks both endpoints as poll-quantized estimates
     * (see `SessionDiffer`), not observed instants — dropping it would misrepresent a range as
     * exact.
     */
    fun sessionRange(
        startAt: Long,
        endAt: Long?,
        open: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val start = timeOfDay(startAt, zone)
        val end = if (open || endAt == null) "now" else timeOfDay(endAt, zone)
        return "~$start – $end"
    }
}
