package com.example.backlogium.ui.analytics

import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Whether a window length is a fixed rolling duration or a named calendar period. */
enum class AnalyticsWindowKind {
    ROLLING,
    CALENDAR,
}

/** Window lengths offered by Analytics. */
enum class AnalyticsWindowLength(
    val label: String,
    val kind: AnalyticsWindowKind,
    val days: Int? = null,
) {
    TWO_WEEKS("2 weeks", AnalyticsWindowKind.ROLLING, days = 14),
    THIRTY_DAYS("30 days", AnalyticsWindowKind.ROLLING, days = 30),
    ONE_MONTH("1 month", AnalyticsWindowKind.CALENDAR),
    NINETY_DAYS("90 days", AnalyticsWindowKind.ROLLING, days = 90),
    ONE_YEAR("1 year", AnalyticsWindowKind.CALENDAR),
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
