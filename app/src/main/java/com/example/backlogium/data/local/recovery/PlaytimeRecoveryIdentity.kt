package com.example.backlogium.data.local.recovery

/**
 * Pure identity and distribution helpers for playtime recovery storage
 * (recover-missed-playtime).
 *
 * No Room, Android or network types appear here so the attribution planner can reuse the same
 * deterministic identities and exact arithmetic without importing storage machinery beyond these
 * plain functions.
 */
object PlaytimeRecoveryIdentity {

    /**
     * The stable identity of a funded contribution. Derived from account + app + baseline epoch +
     * cumulative range — never a poll timestamp or an autoincrement id — so re-observing the same
     * Steam increase yields the same id and idempotent storage, and the identity survives
     * export/import.
     */
    fun contributionId(
        account: String,
        appId: Long,
        baselineEpoch: Long,
        fromMinutes: Long,
        toMinutes: Long,
    ): String = "$account:$appId:$baselineEpoch:$fromMinutes:$toMinutes"

    /**
     * Deterministic placement id for a timed funded share. Revision is part of the identity so a
     * late-evidence plan builds *new* rows and supersedes the old revision's ones; replaying the
     * same revision collides and is a no-op.
     */
    fun timedPlacementId(
        contributionId: String,
        revision: Long,
        evidenceIdentity: String?,
        creditedDate: String,
        sessionId: Long?,
    ): String = "$contributionId:$revision:t:${evidenceIdentity ?: "-"}:$creditedDate:${sessionId ?: "-"}"

    /**
     * Deterministic placement id for an unknown-time allocation. Pending date-unknown quantity uses
     * a null [date] and is keyed by an explicit marker so it can never collide with a dated row.
     */
    fun allocationPlacementId(
        contributionId: String,
        revision: Long,
        date: String?,
    ): String = "$contributionId:$revision:u:${date ?: PENDING_DATE_MARKER}"

    /**
     * Deterministic operation identity for the replacement journal. Produced from the contribution
     * and the evidence that caused the revision, so replaying the same evidence is a no-op.
     */
    fun journalOperationId(
        contributionId: String,
        currentRevision: Long,
        evidenceIdentity: String?,
    ): String = "$contributionId:$currentRevision:op:${evidenceIdentity ?: "-"}"

    const val PENDING_DATE_MARKER = "__PENDING__"
}

/**
 * Exact earliest-date-first residual distribution per design.md Decision 5:
 * `q = R / N`, `r = R % N` gives `q + 1` to the earliest `r` dates and `q` to the rest.
 *
 * Zero-share dates are omitted — they neither create activity rows nor count as active days.
 */
object UnknownTimeDistribution {

    /**
     * @return the positive shares only, keyed by date in chronological order; a date receiving
     *   zero minutes is absent.
     */
    fun distributeExactly(residual: Long, eligibleDates: List<String>): List<Pair<String, Long>> {
        require(residual >= 0L) { "residual must be non-negative: $residual" }
        require(eligibleDates.none { it.isBlank() })
        if (residual == 0L || eligibleDates.isEmpty()) return emptyList()
        val n = eligibleDates.size
        val q = residual / n
        val r = (residual % n).toInt()
        return buildList {
            eligibleDates.forEachIndexed { index, date ->
                val minutes = if (index < r) q + 1 else q
                if (minutes > 0L) add(date to minutes)
            }
        }
    }

    /** The compact recipe form of [distributeExactly]: `floor(R/N)` and `R mod N`. */
    fun quotientAndRemainder(residual: Long, eligibleDateCount: Int): Pair<Long, Long> {
        require(eligibleDateCount > 0 || residual == 0L)
        if (eligibleDateCount == 0) return 0L to 0L
        return (residual / eligibleDateCount) to (residual % eligibleDateCount)
    }
}