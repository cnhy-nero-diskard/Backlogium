package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One day-level unknown-time allocation for a funded contribution (recover-missed-playtime).
 *
 * A dated allocation has an attributed date and credited minutes but deliberately no session-start
 * or session-end: it is day-level activity, not a fabricated timestamped session. A pending
 * allocation (`[date]` null, `pendingDate = true`) holds quantity that cannot be dated — an
 * inverted clock window — until a later trustworthy reading opens a valid window to place it in.
 *
 * [placementId] is deterministic from the contribution identity, revision and the date (or the
 * explicit pending marker), so repeated insertion of the same allocation is a no-op. An earlier
 * revision's rows are retained with [superseded] set, never deleted, to preserve a replaceable
 * audit trail.
 */
@Entity(
    tableName = "recovery_unknown_allocations",
    primaryKeys = ["placementId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaytimeContribution::class,
            parentColumns = ["contributionId"],
            childColumns = ["contributionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["contributionId"]),
        Index(value = ["account", "appId"]),
        Index(value = ["account", "date"]),
    ],
)
data class UnknownTimeAllocation(
    val placementId: String,
    val contributionId: String,
    val account: String,
    val appId: Long,
    val revision: Long,
    /** Frozen attributed local date; null means pending date-unknown quantity. */
    val date: String? = null,
    /** Positive share; `minutes > 0` is enforced by the writer, never a zero-activity row. */
    val minutes: Long,
    val method: String = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
    val zone: String? = null,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null,
    val pendingDate: Boolean = false,
    /**
     * The game's hidden status and Focus/goal status *at credit time*, frozen so an estimate
     * moved by late evidence keeps its original daily quest/Focus eligibility.
     */
    val hiddenAtCredit: Boolean = false,
    val goalAtCredit: Boolean = false,
    val superseded: Boolean = false,
    /** Manually protected; a later automatic upgrade must never replace this allocation. */
    val protected: Boolean = false,
    val createdAt: Long,
)

/**
 * The compact quotient/remainder recipe backing a wide unknown-time window
 * (recover-missed-playtime, design.md Decision 7). One row per contribution holds the exact
 * arithmetic `R = quotient * N + remainder` over `N` eligible dates, so a ten-year gap is stored
 * without one row per day; readers expand only bounded date slices while the authoritative recipe
 * and raw daily credit are committed with the baseline.
 *
 * Only the *current* revision's recipe is retained here; superseded recipes are recorded by the
 * replacement journal, not re-stored per revision.
 */
@Entity(
    tableName = "recovery_allocation_recipes",
    primaryKeys = ["contributionId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaytimeContribution::class,
            parentColumns = ["contributionId"],
            childColumns = ["contributionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UnknownTimeAllocationRecipe(
    val contributionId: String,
    val account: String,
    val appId: Long,
    val revision: Long,
    val method: String,
    /** floor(R / N) for the residual over eligible dates. */
    val quotient: Long? = null,
    /** R mod N — the earliest `remainderCount` eligible dates receive quotient + 1. */
    val remainderCount: Long? = null,
    /** First eligible date, inclusive (after the normalized baseline date). */
    val dateStart: String? = null,
    /** Last eligible date, inclusive (the recovery date). */
    val dateEnd: String? = null,
    val eligibleCount: Int = 0,
    val zone: String? = null,
    val windowStartAt: Long? = null,
    val windowEndAt: Long? = null,
    /** Compact comma-separated ISO dates excluded from residual distribution as fully resolved. */
    val excludedDatesCsv: String? = null,
    val pending: Boolean = false,
    /**
     * How many of this recipe's minutes have been materialized into
     * [UnknownTimeAllocation] rows for the same contribution/revision. Read-back state: the
     * authoritative residual is `quotient * eligibleCount + remainderCount - materializedMinutes`,
     * so a resumable long-window materialization never counts its share twice and never strands
     * minutes. Recomputed from the allocation rows after every slice insert, never incremented.
     */
    val materializedMinutes: Long = 0L,
    /** The last date materialized so far; the next bounded slice starts after it, or null. */
    val materializedThroughDate: String? = null,
    /** The game's Focus/goal and hidden status frozen at credit time (see placement fields). */
    val hiddenAtCredit: Boolean = false,
    val goalAtCredit: Boolean = false,
    val updatedAt: Long,
)

/** How an unknown-time allocation was produced; drives its `!` explanation. */
object RecoveryAllocationMethods {
    const val SINGLE_DAY = "SINGLE_DAY"
    const val MULTI_DAY_ESTIMATE = "MULTI_DAY_ESTIMATE"
    const val PENDING_DATE_UNKNOWN = "PENDING_DATE_UNKNOWN"
}