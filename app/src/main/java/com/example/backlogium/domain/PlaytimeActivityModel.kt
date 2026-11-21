package com.example.backlogium.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Canonical dated play activity exposed at the repository boundary (recover-missed-playtime,
 * design.md Decision 7). Quantity-bearing consumers read credited totals from here while
 * recency, session counts and clock-time metrics keep consuming timestamped sessions alone.
 *
 * Unknown-time activity deliberately has no start/end instant: [DatedAllocation] carries only a
 * frozen attributed date, and the pending date-unknown badge none at all. An observation instant is
 * never presented as a gameplay boundary (playtime-recovery spec "An observation instant SHALL NOT
 * be presented or exported as a gameplay boundary"). Every dated allocation retains the identity
 * and window context its `!` explanation needs: contribution id, frozen zone, the residual window's
 * date span and total, this selected day's share and the original observed window endpoints.
 */
sealed interface PlaytimeActivity {
    val appId: Long

    /**
     * A real timestamped session row shown once under its own start date (session-shape contract).
     * [fundedShares] are the active funded timed shares hosted inside this row, each frozen to its
     * own credited date; [fundedMinutes] is their sum and [unlinkedMinutes] the legacy remainder
     * (`minutes - fundedMinutes`). The authoritative per-date quantity comes from
     * [PlaytimeReadProjector.datedCreditedTotals], which credits the unlinked remainder to the
     * session's current-zone start date and *each* funded share to its frozen date exactly once —
     * so the split is exposed here without the UI ever adding the same minute twice.
     */
    data class TimestampedSession(
        override val appId: Long,
        val id: Long,
        val startAt: Long,
        val endAt: Long?,
        val minutes: Int,
        val open: Boolean,
        val fundedShares: List<SessionFundedShare>,
        val fundedMinutes: Long,
        val unlinkedMinutes: Long,
    ) : PlaytimeActivity

    /**
     * Day-level unknown-time activity: credited minutes on one frozen local date, method and
     * window context — no clock boundaries of any kind. Produced identically from materialized
     * allocation rows, detached timed shares and recipe-derived residual slices.
     */
    data class DatedAllocation(
        override val appId: Long,
        val contributionId: String,
        val date: LocalDate,
        /** This selected day's credited share — the authoritative amount for [date]. */
        val selectedDayMinutes: Long,
        /** Frozen allocation zone the date keys and window dates were normalized in. */
        val frozenZone: String?,
        /** First frozen date of the residual window this share belongs to (== [date] for single-day). */
        val windowDateStart: LocalDate?,
        /** Last frozen date of the residual window (== [date] for single-day). */
        val windowDateEnd: LocalDate?,
        /** The residual window's total recovered minutes, for `!` explanations. */
        val residualWindowTotalMinutes: Long,
        val method: PlaytimeAllocationMethod,
        /** Materialized row's placement id, or a deterministic composite for recipe-derived rows. */
        val placementId: String? = null,
        /** Frozen credit-time Focus/goal eligibility for the owning contribution share. */
        val goalAtCredit: Boolean? = null,
        /** Original observed window endpoints; evidence only, never gameplay boundaries. */
        val windowStartAt: Long? = null,
        val windowEndAt: Long? = null,
        /** Set when this row backs a detached funded timed share, to carry its evidence quality. */
        val evidenceQuality: String? = null,
    ) : PlaytimeActivity {
        val minutes: Long get() = selectedDayMinutes
    }

    /** Pending date-unknown quantity: counts once in cumulative XP, never a dated quest or habit. */
    data class PendingDateUnknown(
        override val appId: Long,
        val minutes: Long,
    ) : PlaytimeActivity
}

/** One funded timed share hosted inside a session, frozen to its own credited date. */
data class SessionFundedShare(
    val contributionId: String,
    val minutes: Long,
    /** Frozen attribution date of this share; may differ from the session's own start date. */
    val creditedDate: LocalDate?,
    /** Frozen zone used for the credited date. */
    val frozenZone: String? = null,
    /** Credit-time Focus eligibility; null is reserved for legacy/test projections. */
    val goalAtCredit: Boolean? = null,
)

/**
 * One dated unknown-time allocation as the History/Analytics leaves view it (recover-missed-
 * playtime tasks 7.1/7.4). Every field the `!` explanation needs is retained: the frozen
 * allocation zone, the residual window's date span and total, this day's share and the original
 * observation window. Pending date-unknown quantity is never represented here.
 */
data class CreditedAllocation(
    val placementId: String,
    val appId: Long,
    val date: LocalDate,
    val minutes: Int,
    val method: PlaytimeAllocationMethod,
    val zone: String?,
    val windowStartDate: LocalDate?,
    val windowEndDate: LocalDate?,
    val windowTotalMinutes: Int,
    val goalAtCredit: Boolean?,
)

/** Honest recovery-availability vocabulary disclosed alongside dated rows (tasks 7.4). */
enum class PlaytimeRecoveryStatus { KNOWN_GOOD, REMOTE_UNAVAILABLE, OBSERVED_AWAITING_STEAM, UNCHANGED_NO_KNOWN_PLAY }

/** One local date's canonical credited total with its per-game split (Analytics/History). */
data class DatedCreditedTotal(
    val date: LocalDate,
    val minutes: Long,
    val minutesByGame: Map<Long, Long>,
)

/**
 * An unresolved multi-day estimate window for Personal Pace eligibility (design.md Decision 9).
 * Every date in [dateStart]..[dateEnd] — including zero-share and mixed dates — is unknown for
 * habit training unless it is in [fullyResolvedDates] (independently fully resolved by validated
 * placement). Excluded dates therefore restore eligibility; merely filtering estimate rows instead
 * would synthesize invented zero-play observations for the rest of the window.
 *
 * Date masking is bounded on demand: a ten-year estimate window must never be enumerated in full,
 * so callers use [unknownDatesBetween] over their own horizon.
 */
data class RecoveryEstimateWindow(
    val contributionId: String,
    val appId: Long,
    val dateStart: LocalDate,
    val dateEnd: LocalDate,
    val fullyResolvedDates: Set<LocalDate> = emptySet(),
    /** Compact fully-resolved ranges from long recipes; never expand them outside a bounded query. */
    val fullyResolvedDateRanges: List<ClosedRange<LocalDate>> = emptyList(),
) {
    init {
        require(!dateEnd.isBefore(dateStart)) { "estimate window must not be inverted" }
        require(fullyResolvedDates.all { !it.isBefore(dateStart) && !it.isAfter(dateEnd) }) {
            "fully resolved dates must lie inside the estimate window"
        }
        require(fullyResolvedDateRanges.all {
            !it.start.isBefore(dateStart) && !it.endInclusive.isAfter(dateEnd) &&
                !it.endInclusive.isBefore(it.start)
        }) { "fully resolved ranges must lie inside the estimate window" }
    }

    /** Whether [date] is a window date whose placement is not yet fully resolved. */
    fun isUnknown(date: LocalDate): Boolean = date in dateStart..dateEnd && date !in fullyResolvedDates
        && fullyResolvedDateRanges.none { date >= it.start && date <= it.endInclusive }

    /** Unresolved window dates clipped to [start, endInclusive]; work is bounded by that slice. */
    fun unknownDatesBetween(start: LocalDate, endInclusive: LocalDate): Set<LocalDate> {
        val first = maxOf(start, dateStart)
        val last = minOf(endInclusive, dateEnd)
        if (last.isBefore(first)) return emptySet()
        return (0L..ChronoUnit.DAYS.between(first, last))
            .asSequence()
            .map { first.plusDays(it) }
            .filterNot { isResolved(it) }
            .toSet()
    }

    private fun isResolved(date: LocalDate): Boolean = date in fullyResolvedDates ||
        fullyResolvedDateRanges.any { date >= it.start && date <= it.endInclusive }
}
