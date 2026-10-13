package com.example.backlogium.data.repo

import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.domain.DailyProgressCredit
import com.example.backlogium.domain.PlaytimeAllocationMethod
import com.example.backlogium.domain.PlaytimeAttributionPlan
import com.example.backlogium.domain.PlaytimeEvidenceSource
import com.example.backlogium.domain.RecoveryContributionSnapshot
import com.example.backlogium.domain.ResidualDateRecipe
import com.example.backlogium.domain.SessionDiffer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure mapping from one [PlaytimeAttributionPlan] to the recovery storage rows the committer
 * commits atomically with its baseline (recover-missed-playtime, design.md Decisions 2/5/7).
 *
 * No Room, no Android types: every function here is deterministic in the plan plus the caller's
 * frozen credit-time context, so it is unit-testable without a database, and replaying an
 * identical plan yields identical deterministic placement ids (which storage then ignores).
 *
 * Every authoritative range becomes one funded contribution. Ordinary timestamped sessions are
 * funded by a *session share* — a timed placement whose minutes are the session action's
 * `addedMinutes`, linked to the actual persisted session id — so a mixed/extended row keeps its
 * unlinked legacy remainder while the funded portion is documented exactly once. Evidence-informed
 * shares are timed placements; unmatched minutes become dated unknown-time allocations plus a
 * compact quotient/remainder recipe. The recipe is stored for **every** residual window, even when
 * its positive dates are also materialized inline, so exclusions/zero-share gaps and provenance
 * survive and the storage layer counts the residual exactly once (never recipe + rows).
 */
object PlaytimeRecoveryCommitMapper {

    /** Windows at or below this many calendar dates are materialized inline as day rows. */
    const val INLINE_MAX_DATES = 92

    /** Everything the converter needs from the commit site, frozen at credit time. */
    data class RecoveryCommitContext(
        /** The previous usable observation instant that opened this plan's window. */
        val windowStartAt: Long?,
        /** The recovery *reading* instant that closed the window (the caller's `syncedAt`). */
        val windowEndAt: Long,
        /** The game's hidden status when the credit was authorized; never re-read later. */
        val hiddenAtCredit: Boolean,
        /** The game's Focus/goal status when the credit was authorized; never re-read later. */
        val goalAtCredit: Boolean,
        /** Crediting instant (the caller's `syncedAt`), used as creation timestamps. */
        val now: Long,
    )

    /** What committing one plan produced, for the committer's accounting. */
    data class GraphOutcome(
        val committed: Boolean = false,
        /** (appId, startAt) pairs of evidence-informed sessions to mark timing-informed. */
        val timedSessionKeys: Set<Pair<Long, Long>> = emptySet(),
        /** Raw daily credit for the *dated* allocations of this window (frozen eligibility). */
        val allocationCreditByDate: Map<String, DailyProgressCredit> = emptyMap(),
        /** Exact eligible per-date credits authored by this fresh contribution, for mixed staging. */
        val earnedCreditByDate: Map<String, DailyProgressCredit> = emptyMap(),
        /** Dates whose raw daily credit this operation touched (compact for long recipes). */
        val changedDates: List<String> = emptyList(),
        /** Durable journal key for this raw contribution operation. */
        val operationId: String? = null,
        /** Exact authorized contribution facts for staged derived finalization. */
        val contributionSnapshot: RecoveryContributionSnapshot? = null,
        /** This graph needs a corrective stage rather than a plain earned-only sync. */
        val requiresFinalization: Boolean = false,
        /** A current-date SYNC contribution that remains an earned input in a mixed operation. */
        val earnedContribution: Boolean = false,
    )

    /** The storage primary key for a plan's contribution identity. */
    fun storageContributionId(plan: PlaytimeAttributionPlan): String? = plan.contributionId?.let {
        PlaytimeRecoveryIdentity.contributionId(
            it.account, it.appId, it.baselineEpoch,
            it.quantityRange.fromMinutes.toLong(), it.quantityRange.toMinutes.toLong(),
        )
    }

    /**
     * The contribution row for a plan. A pending/replacement commit preserves the stored row's
     * window *and* its frozen zone (the authoritative observation window/zone of the retained
     * contribution), because the quantity identity must never move between windows; a fresh plan
     * carries its own window and zone.
     */
    fun contributionRow(
        plan: PlaytimeAttributionPlan,
        context: RecoveryCommitContext,
        existing: PlaytimeContribution?,
        revision: Long,
    ): PlaytimeContribution? {
        val id = plan.contributionId ?: return null
        return PlaytimeContribution(
            contributionId = storageContributionId(plan)!!,
            account = plan.account,
            appId = plan.appId,
            baselineEpoch = plan.baselineEpoch,
            fromMinutes = id.quantityRange.fromMinutes.toLong(),
            toMinutes = id.quantityRange.toMinutes.toLong(),
            // A retained identity keeps the exact original window, including an explicitly
            // unknown start. Elvis fallback would turn a pending null anchor into a later window
            // on replan and make the same cumulative range appear to have changed identity.
            windowStartAt = if (existing != null) existing.windowStartAt else context.windowStartAt,
            windowEndAt = if (existing != null) existing.windowEndAt else context.windowEndAt,
            authorizedMinutes = id.quantityRange.authorizedMinutes.toLong(),
            revision = revision,
            provenance = plan.provenance.name,
            zone = if (existing != null) existing.zone else plan.zone.toString(),
            observedZone = if (existing != null) existing.observedZone else plan.zone.toString(),
            createdAt = context.now,
        )
    }

    /** The frozen zone a plan's shares are dated in — the contribution's, once it exists. */
    fun frozenZone(contribution: PlaytimeContribution, plan: PlaytimeAttributionPlan): ZoneId =
        contribution.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: plan.zone

    /** Sessions for each evidence-informed placement: bounded, then closed at its confirmed end. */
    fun timedSessionActions(plan: PlaytimeAttributionPlan): List<SessionDiffer.SessionAction> =
        plan.timedPlacements.flatMap { placement ->
            listOf(
                SessionDiffer.SessionAction.Open(
                    appId = plan.appId,
                    startAt = placement.startAt,
                    endAt = placement.endAt,
                    minutes = placement.minutes,
                ),
                SessionDiffer.SessionAction.Close(
                    appId = plan.appId,
                    startAt = placement.startAt,
                    endAt = placement.endAt,
                ),
            )
        }

    /** (appId, startAt) keys the writer should mark timing-informed for this plan's sessions. */
    fun timedSessionKeys(plan: PlaytimeAttributionPlan): Set<Pair<Long, Long>> =
        plan.timedPlacements.mapTo(mutableSetOf()) { plan.appId to it.startAt }

    /**
     * Evidence-informed timed shares. [evidenceIdentity] is stored as structured metadata —
     * `id|startAt|endAt` — so consumed-span reconstruction knows the *actual funded interval*, not
     * the possibly-wider session row it was placed into, without adding storage columns.
     */
    fun evidenceTimedRows(
        plan: PlaytimeAttributionPlan,
        contribution: PlaytimeContribution,
        revision: Long,
        context: RecoveryCommitContext,
        sessionLookup: (startAt: Long, endAt: Long) -> Long?,
    ): List<TimedContributionPlacement> = plan.timedPlacements.map { placement ->
        val identity = structuredEvidenceIdentity(
            placement.evidenceIds.firstOrNull() ?: UNKNOWN_EVIDENCE,
            placement.startAt,
            placement.endAt,
        )
        timedRow(
            plan = plan,
            contribution = contribution,
            revision = revision,
            context = context,
            minutes = placement.minutes.toLong(),
            startAt = placement.startAt,
            endAt = placement.endAt,
            evidenceIdentity = identity,
            evidenceQuality = when (placement.source) {
                PlaytimeEvidenceSource.LOCAL -> RecoveryEvidenceQuality.LOCAL
                PlaytimeEvidenceSource.CLOUD -> RecoveryEvidenceQuality.CLOUD
                PlaytimeEvidenceSource.ESTIMATE -> RecoveryEvidenceQuality.ESTIMATE
            },
            sessionId = sessionLookup(placement.startAt, placement.endAt),
        )
    }

    /**
     * Funded shares for the plan's ordinary timestamped sessions: each Open/Extend contributes its
     * `addedMinutes` — never the whole (possibly mixed) row — so the session's unlinked legacy
     * remainder stays with the row and the funded portion is documented once, linked to the actual
     * persisted session id.
     */
    fun sessionShareRows(
        plan: PlaytimeAttributionPlan,
        contribution: PlaytimeContribution,
        revision: Long,
        context: RecoveryCommitContext,
        sessionLookup: (startAt: Long, endAt: Long) -> Long?,
    ): List<TimedContributionPlacement> = plan.sessionActions
        .filter { it.addedMinutes > 0 }
        .map { action ->
            val fundedStart = context.windowStartAt ?: action.startAt
            val identity = structuredEvidenceIdentity("session", fundedStart, context.windowEndAt)
            timedRow(
                plan = plan,
                contribution = contribution,
                revision = revision,
                context = context,
                minutes = action.addedMinutes.toLong(),
                startAt = action.startAt,
                endAt = action.storedEndAt,
                evidenceIdentity = identity,
                evidenceQuality = RecoveryEvidenceQuality.ESTIMATE,
                sessionId = sessionLookup(action.startAt, action.storedEndAt),
            )
        }

    private fun timedRow(
        plan: PlaytimeAttributionPlan,
        contribution: PlaytimeContribution,
        revision: Long,
        context: RecoveryCommitContext,
        minutes: Long,
        startAt: Long,
        endAt: Long,
        evidenceIdentity: String,
        evidenceQuality: String,
        sessionId: Long?,
    ): TimedContributionPlacement {
        val zone = frozenZone(contribution, plan)
        return TimedContributionPlacement(
            placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                contributionId = contribution.contributionId,
                revision = revision,
                evidenceIdentity = evidenceIdentity,
                creditedDate = localDate(startAt, zone).toString(),
                sessionId = sessionId,
            ),
            contributionId = contribution.contributionId,
            account = contribution.account,
            appId = contribution.appId,
            revision = revision,
            sessionId = sessionId,
            minutes = minutes,
            creditedDate = localDate(startAt, zone).toString(),
            zone = zone.toString(),
            evidenceIdentity = evidenceIdentity,
            evidenceQuality = evidenceQuality,
            hiddenAtCredit = context.hiddenAtCredit,
            goalAtCredit = context.goalAtCredit,
            createdAt = context.now,
        )
    }

    /**
     * The funded interval a stored [TimedContributionPlacement.evidenceIdentity] documents, or
     * null when the identity predates structured metadata. Parsed defensively: a malformed
     * identity consumes nothing.
     */
    fun consumedSpanFrom(timed: TimedContributionPlacement): Pair<Long, Long>? {
        val identity = timed.evidenceIdentity ?: return null
        val localOrSession = identity.split('|')
        if (localOrSession.size >= 3) {
            val from = localOrSession[localOrSession.lastIndex - 1].toLongOrNull()
            val to = localOrSession.last().toLongOrNull()
            if (from != null && to != null && to > from) return from to to
        }
        val cloud = identity.split(':')
        if (cloud.size >= 4 && cloud.first() == "cloud") {
            val from = cloud[2].toLongOrNull()
            val to = cloud[3].toLongOrNull()
            if (from != null && to != null && to > from) return from to to
        }
        return null
    }

    /** `id|startAt|endAt` — deterministic in the plan, so a replay produces the same rows. */
    internal fun structuredEvidenceIdentity(id: String, startAt: Long, endAt: Long): String =
        "$id|$startAt|$endAt"

    private val SessionDiffer.SessionAction.storedEndAt: Long
        get() = when (this) {
            is SessionDiffer.SessionAction.Open -> endAt
            is SessionDiffer.SessionAction.Extend -> endAt
            is SessionDiffer.SessionAction.Close -> endAt
        }

    internal const val UNKNOWN_EVIDENCE = "unknown"

    /**
     * The unknown-time rows for a plan plus its compact recipe. A pending plan yields its pending
     * row only. A dated residual always yields a recipe (so exclusions/zero-share gaps and
     * provenance survive and storage counts it once); a bounded window additionally materializes
     * every positive date inline, which the rank-based storage validation accepts as a complete
     * proven prefix.
     */
    fun allocationsAndRecipe(
        plan: PlaytimeAttributionPlan,
        contribution: PlaytimeContribution,
        revision: Long,
        context: RecoveryCommitContext,
    ): Pair<List<UnknownTimeAllocation>, UnknownTimeAllocationRecipe?> {
        val zone = frozenZone(contribution, plan)
        if (plan.pending != null) {
            val pendingRow = UnknownTimeAllocation(
                placementId = PlaytimeRecoveryIdentity.allocationPlacementId(
                    contribution.contributionId, revision, null,
                ),
                contributionId = contribution.contributionId,
                account = contribution.account,
                appId = contribution.appId,
                revision = revision,
                date = null,
                minutes = plan.pending.minutes.toLong(),
                method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN,
                zone = zone.toString(),
                windowStartAt = context.windowStartAt,
                windowEndAt = context.windowEndAt,
                pendingDate = true,
                hiddenAtCredit = context.hiddenAtCredit,
                goalAtCredit = context.goalAtCredit,
                createdAt = context.now,
            )
            return listOf(pendingRow) to null
        }

        val residual = plan.residual ?: return emptyList<UnknownTimeAllocation>() to null
        val recipe = recipeRow(plan, contribution, revision, context, residual, zone)
        if (residual.windowDateCount > INLINE_MAX_DATES) {
            return emptyList<UnknownTimeAllocation>() to recipe
        }
        val rows = plan.residualAllocations().map { allocation ->
            UnknownTimeAllocation(
                placementId = PlaytimeRecoveryIdentity.allocationPlacementId(
                    contribution.contributionId, revision, allocation.date.toString(),
                ),
                contributionId = contribution.contributionId,
                account = contribution.account,
                appId = contribution.appId,
                revision = revision,
                date = allocation.date.toString(),
                minutes = allocation.minutes.toLong(),
                method = methodString(allocation.method),
                zone = zone.toString(),
                windowStartAt = context.windowStartAt,
                windowEndAt = context.windowEndAt,
                hiddenAtCredit = context.hiddenAtCredit,
                goalAtCredit = context.goalAtCredit,
                createdAt = context.now,
            )
        }
        return rows to recipe
    }

    private fun recipeRow(
        plan: PlaytimeAttributionPlan,
        contribution: PlaytimeContribution,
        revision: Long,
        context: RecoveryCommitContext,
        residual: ResidualDateRecipe,
        zone: ZoneId,
    ) = UnknownTimeAllocationRecipe(
        contributionId = contribution.contributionId,
        account = contribution.account,
        appId = contribution.appId,
        revision = revision,
        method = if (residual.method == PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE) {
            RecoveryAllocationMethods.MULTI_DAY_ESTIMATE
        } else {
            RecoveryAllocationMethods.SINGLE_DAY
        },
        quotient = residual.baseMinutes.toLong(),
        remainderCount = residual.remainderCount.toLong(),
        dateStart = residual.firstDate.toString(),
        dateEnd = residual.lastDate.toString(),
        eligibleCount = residual.eligibleDateCount,
        zone = zone.toString(),
        windowStartAt = context.windowStartAt,
        windowEndAt = context.windowEndAt,
        excludedDatesCsv = excludedDatesCsv(residual),
        pending = false,
        hiddenAtCredit = context.hiddenAtCredit,
        goalAtCredit = context.goalAtCredit,
        updatedAt = context.now,
    )

    /**
     * Raw daily credit for this plan's *dated* allocations, driven by the frozen hidden/Focus
     * eligibility captured at credit time rather than today's settings. Pending quantity and
     * long-recipe windows contribute nothing here — the materializer credits those dates as it
     * expands them (tasks 5.4).
     */
    fun allocationDailyCredit(
        plan: PlaytimeAttributionPlan,
        hiddenAtCredit: Boolean,
        goalAtCredit: Boolean,
    ): Map<String, DailyProgressCredit> {
        val residual = plan.residual ?: return emptyMap()
        if (hiddenAtCredit) return emptyMap()
        if (residual.windowDateCount > INLINE_MAX_DATES) return emptyMap()
        return plan.residualAllocations()
            .groupBy { it.date.toString() }
            .mapValues { (_, shares) ->
                val minutes = shares.sumOf { it.minutes }
                DailyProgressCredit(
                    minutesPlayed = minutes,
                    goalMinutesPlayed = if (goalAtCredit) minutes else 0,
                )
            }
    }

    /**
     * Per-date raw credit owned by a plan. This includes session-shaped and evidence-timed shares
     * as well as inline dated allocations; pending and long-window plans are corrective and never
     * enter the fresh-earned exclusion payload.
     */
    fun datedCreditByDate(
        plan: PlaytimeAttributionPlan,
        hiddenAtCredit: Boolean,
        goalAtCredit: Boolean,
        appliedSessionActions: List<SessionDiffer.SessionAction>,
    ): Map<String, DailyProgressCredit> {
        if (hiddenAtCredit) return emptyMap()
        val credits = linkedMapOf<String, DailyProgressCredit>()
        fun add(date: LocalDate, minutes: Int) {
            if (minutes <= 0) return
            val key = date.toString()
            val current = credits[key]
            credits[key] = DailyProgressCredit(
                minutesPlayed = Math.addExact(current?.minutesPlayed ?: 0, minutes),
                goalMinutesPlayed = Math.addExact(
                    current?.goalMinutesPlayed ?: 0,
                    if (goalAtCredit) minutes else 0,
                ),
            )
        }
        appliedSessionActions.forEach { action ->
            add(Instant.ofEpochMilli(action.startAt).atZone(plan.zone).toLocalDate(), action.addedMinutes)
        }
        plan.timedPlacements.forEach { placement -> add(placement.date, placement.minutes) }
        if ((plan.residual?.windowDateCount ?: 0) <= INLINE_MAX_DATES) {
            allocationDailyCredit(plan, hiddenAtCredit, goalAtCredit).forEach { (date, credit) ->
                val current = credits[date]
                credits[date] = DailyProgressCredit(
                    minutesPlayed = Math.addExact(current?.minutesPlayed ?: 0, credit.minutesPlayed),
                    goalMinutesPlayed = Math.addExact(
                        current?.goalMinutesPlayed ?: 0,
                        credit.goalMinutesPlayed,
                    ),
                )
            }
        }
        return credits
    }

    /**
     * The dates this plan's derived finalization must re-derive. A long window is represented
     * compactly (`RANGE:first:last:count`) instead of materializing thousands of ISO dates.
     */
    fun journalAffectedDates(plan: PlaytimeAttributionPlan): List<String> {
        val residual = plan.residual
        if (residual != null && residual.windowDateCount > INLINE_MAX_DATES) {
            return listOf(
                "RANGE:${residual.firstDate}:${residual.lastDate}:${residual.windowDateCount}",
            )
        }
        return plan.changedDates().map { it.toString() }.distinct().sorted()
    }

    private fun methodString(method: PlaytimeAllocationMethod): String = when (method) {
        PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE,
        PlaytimeAllocationMethod.KNOWN_TARGETED_DATE,
        -> RecoveryAllocationMethods.SINGLE_DAY
        PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE -> RecoveryAllocationMethods.MULTI_DAY_ESTIMATE
        PlaytimeAllocationMethod.EVIDENCE_INFORMED -> RecoveryAllocationMethods.SINGLE_DAY
        PlaytimeAllocationMethod.PENDING_DATE_UNKNOWN -> RecoveryAllocationMethods.PENDING_DATE_UNKNOWN
    }

    private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private fun excludedDatesCsv(residual: ResidualDateRecipe): String? {
        if (residual.excludedRanges.isEmpty()) return null
        return residual.excludedRanges.joinToString(",") { range ->
            if (range.start == range.endInclusive) range.start.toString()
            else "${range.start}..${range.endInclusive}"
        }
    }
}
