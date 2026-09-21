package com.example.backlogium.ui.analytics

import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Whether a window length is a fixed rolling duration or a named calendar period. */
enum class AnalyticsWindowKind {
    ROLLING,
    CALENDAR,
}

/** Window lengths offered by Analytics. */
enum class AnalyticsWindowLength(
    val kind: AnalyticsWindowKind,
    val days: Int? = null,
) {
    TWO_WEEKS(AnalyticsWindowKind.ROLLING, days = 14),
    THIRTY_DAYS(AnalyticsWindowKind.ROLLING, days = 30),
    ONE_MONTH(AnalyticsWindowKind.CALENDAR),
    NINETY_DAYS(AnalyticsWindowKind.ROLLING, days = 90),
    ONE_YEAR(AnalyticsWindowKind.CALENDAR),
}

/** A selected Analytics window: an anchor date and the length used to resolve it. */
data class AnalyticsWindow(
    val anchor: LocalDate,
    val length: AnalyticsWindowLength,
) {
    /** The full local-date bounds represented by this window, inclusive at both ends. */
    fun resolve(): AnalyticsWindowBounds = when (length) {
        AnalyticsWindowLength.TWO_WEEKS,
        AnalyticsWindowLength.THIRTY_DAYS,
        AnalyticsWindowLength.NINETY_DAYS,
        -> {
            val dayCount = requireNotNull(length.days)
            AnalyticsWindowBounds(
                start = anchor.minusDays((dayCount - 1).toLong()),
                endInclusive = anchor,
            )
        }

        AnalyticsWindowLength.ONE_MONTH -> {
            val month = YearMonth.from(anchor)
            AnalyticsWindowBounds(
                start = month.atDay(1),
                endInclusive = month.atEndOfMonth(),
            )
        }

        AnalyticsWindowLength.ONE_YEAR -> {
            val year = Year.from(anchor)
            AnalyticsWindowBounds(
                start = year.atDay(1),
                endInclusive = year.atMonth(12).atEndOfMonth(),
            )
        }
    }

    /** Move to the immediately preceding window, with no gap or overlap for rolling lengths. */
    fun stepEarlier(): AnalyticsWindow = copy(
        anchor = when (length) {
            AnalyticsWindowLength.ONE_MONTH -> YearMonth.from(anchor).minusMonths(1).atDay(1)
            AnalyticsWindowLength.ONE_YEAR -> Year.from(anchor).minusYears(1).atDay(1)
            else -> anchor.minusDays(requireNotNull(length.days).toLong())
        },
    )

    /** Move to the immediately following window. The screen uses this for testable symmetry. */
    fun stepLater(): AnalyticsWindow = copy(
        anchor = when (length) {
            AnalyticsWindowLength.ONE_MONTH -> YearMonth.from(anchor).plusMonths(1).atDay(1)
            AnalyticsWindowLength.ONE_YEAR -> Year.from(anchor).plusYears(1).atDay(1)
            else -> anchor.plusDays(requireNotNull(length.days).toLong())
        },
    )

    /** True when stepping earlier would still leave the earliest tracked date inside the window. */
    fun canStepEarlier(earliestTrackedDate: LocalDate?): Boolean =
        earliestTrackedDate != null && stepEarlier().resolve().endInclusive >= earliestTrackedDate

    /** True when this window resolves to the period containing [today]. */
    fun isCurrentWindow(today: LocalDate): Boolean =
        resolve() == AnalyticsWindow(today, length).resolve()

    /** True when one valid step later would not move beyond the current period. */
    fun canStepLater(today: LocalDate): Boolean =
        !isCurrentWindow(today) &&
            stepLater().resolve().endInclusive <= AnalyticsWindow(today, length).resolve().endInclusive

    /**
     * Bounds of the immediately preceding comparable window, or null when no equal-duration
     * comparison exists.
     *
     * Rolling lengths always compare equal-length windows. Past calendar periods compare full
     * periods. The current (still elapsing) calendar month/year compares only the same elapsed
     * day-count in the prior period, so month-to-date is never presented against a full month
     * (and likewise year-to-date against a full year). When the elapsed day-count does not fit
     * inside the prior period — a longer current month than the previous month, or a leap-year
     * Dec 31 against a 365-day prior year — there is no equal-duration subrange, so the result
     * is null and callers omit the previous-period headline instead of comparing unequal totals.
     */
    fun comparablePreviousBounds(today: LocalDate): AnalyticsWindowBounds? {
        val previousFull = stepEarlier().resolve()
        if (length.kind != AnalyticsWindowKind.CALENDAR) return previousFull
        if (!isCurrentWindow(today)) return previousFull
        val currentBounds = resolve()
        val elapsedEnd =
            if (today.isBefore(currentBounds.endInclusive)) today else currentBounds.endInclusive
        val elapsedDays =
            ChronoUnit.DAYS.between(currentBounds.start, elapsedEnd).toInt() + 1
        if (elapsedDays > previousFull.dayCount) return null
        return AnalyticsWindowBounds(
            start = previousFull.start,
            endInclusive = previousFull.start.plusDays((elapsedDays - 1).coerceAtLeast(0).toLong()),
        )
    }

    /**
     * Return the comparable previous bounds only when both sides are fully observed since tracking
     * began. A partial first window must not be presented as a period comparison.
     */
    fun comparablePreviousBoundsIfFullyObserved(
        today: LocalDate,
        earliestTrackedDate: LocalDate?,
    ): AnalyticsWindowBounds? {
        val previous = comparablePreviousBounds(today) ?: return null
        val current = resolveActivityBounds(today)
        return previous.takeIf { bounds ->
            earliestTrackedDate != null &&
                !earliestTrackedDate.isAfter(current.start) &&
                !earliestTrackedDate.isAfter(bounds.start)
        }
    }

    /**
     * The local-date bounds actually represented as activity: the full calendar period for past
     * windows, but only the elapsed subrange through [today] for the current calendar month/year.
     * Rolling lengths already end on their anchor, so clamping is a no-op for them; past calendar
     * periods end on or before [today] and are returned unchanged. Future dates never appear as
     * zero-activity days.
     */
    fun resolveActivityBounds(today: LocalDate): AnalyticsWindowBounds {
        val full = resolve()
        if (!full.endInclusive.isAfter(today)) return full
        if (full.start.isAfter(today)) return full
        return AnalyticsWindowBounds(start = full.start, endInclusive = today)
    }
}

/** Explicit inclusive local-date bounds for a selected Analytics window. */
data class AnalyticsWindowBounds(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(start)) {
            "endInclusive must not be before start"
        }
    }

    val dayCount: Int
        get() = ChronoUnit.DAYS.between(start, endInclusive).toInt() + 1

    fun dates(): List<LocalDate> = (0 until dayCount).map { start.plusDays(it.toLong()) }
}

/** Locale-aware period text; the [LocalDate] bounds themselves remain unchanged. */
fun formatAnalyticsWindowPeriod(
    window: AnalyticsWindow,
    bounds: AnalyticsWindowBounds,
    locale: Locale = Locale.getDefault(),
): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    return when (window.length.kind) {
        AnalyticsWindowKind.CALENDAR -> {
            if (bounds.start.monthValue == 1 && bounds.endInclusive.monthValue == 12) {
                DateTimeFormatter.ofPattern("yyyy", locale).format(bounds.start)
            } else if (bounds.start.year == bounds.endInclusive.year) {
                DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(bounds.start)
            } else {
                "${formatter.format(bounds.start)} - ${formatter.format(bounds.endInclusive)}"
            }
        }
        AnalyticsWindowKind.ROLLING ->
            "${formatter.format(bounds.start)} - ${formatter.format(bounds.endInclusive)}"
    }
}
