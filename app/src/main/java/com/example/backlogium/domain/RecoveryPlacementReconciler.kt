package com.example.backlogium.domain

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.local.recovery.UnknownTimeDistribution
import com.example.backlogium.data.repo.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Idempotent, revision-based late-evidence upgrades for retained playtime-recovery contributions
 * (recover-missed-playtime, tasks 6.2/6.3; design.md Decision 6).
 *
 * Steam is never re-authorized here: the contribution already holds its authorized minutes at its
 * frozen revision, and this reconciler only re-places where those minutes live. Validated timing
 * evidence acquired after the Steam baseline was retired moves unlocked **estimated** shares to
 * evidence-informed placement (a timestamped session), redistributes the residual across the
 * contribution's retained, frozen dates, supersedes the old revision's placement graph, records the
 * new revision in the durable journal, and recomputes the affected old/new daily dates — all while
 * conserving `authorized = active timed shares + active dated allocations + pending shares`.
 *
 * Nothing here infers a contribution window from session timestamps: the window is the
 * contribution's own retained `windowStartAt`/`windowEndAt` frozen with its zone. Manually
 * protected placements, already evidence-informed (LOCAL/CLOUD) shares, known-targeted-date
 * allocations and unprovable legacy session minutes are never moved; a session is reduced or
 * retired only by the exact funded share that is provably being replaced, and only when no
 * independent state still references it.
 *
 * Replaying identical evidence is a structural no-op: once placed, the share is locked at the new
 * revision and its spans are consumed, so a duplicate page, a restart, or a concurrent second
 * transaction computes an empty upgrade and changes nothing.
 */
object RecoveryPlacementReconciler {

    const val RESIDUAL_MATERIALIZE_MAX_DAYS = 370

    /** Unknown-time allocation methods that represent *estimates* an upgrade may re-place. */
    private val UNLOCKED_ALLOCATION_METHODS = setOf(
        RecoveryAllocationMethods.SINGLE_DAY,
        RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
        RecoveryAllocationMethods.PENDING_DATE_UNKNOWN,
    )

    // ------------------------------------------------------------------ pure plan shapes

    /** One evidence-informed replacement session the upgrade will insert (minutes are funded). */
    data class NewEvidencePlacement(
        val startAt: Long,
        val endAt: Long,
        val minutes: Int,
        val creditedDate: String,
        val evidenceIdentity: String,
        val evidenceQuality: String,
        val hiddenAtCredit: Boolean,
        val goalAtCredit: Boolean,
    )

    /** A mapped synthetic session reduced by exactly the funded share being moved out. */
    data class SessionShareChange(
        val sessionId: Long,
        val beforeMinutes: Int,
        val afterMinutes: Int,
    )

    /**
     * The complete, storage-shaped replacement for a contribution's placement graph at the next
     * revision. [operation] is NO_CHANGE when nothing usable is unlocked or the evidence places
     * nothing new, so a replayed plan never writes.
     */
    data class RecoveryPlacementReconcilePlan(
        val contributionId: String,
        val account: String,
        val appId: Long,
        val operation: RecoveryReconcileOperation,
        val priorRevision: Long,
        val currentRevision: Long,
        /** Locked funded shares re-declared at the current revision (ids embed the revision). */
        val keptTimedShares: List<TimedContributionPlacement> = emptyList(),
        /** Evidence-informed sessions to insert (their funded shares live in [newEvidencePlacements]). */
        val newEvidencePlacements: List<NewEvidencePlacement> = emptyList(),
        /** Locked dated allocations re-declared at the current revision. */
        val keptAllocationRows: List<UnknownTimeAllocation> = emptyList(),
        /** Redistributed residual dated allocations at the current revision. */
        val newAllocationRows: List<UnknownTimeAllocation> = emptyList(),
        /** Compact recipe for a long residual window (supersedes row materialization). */
        val newRecipe: UnknownTimeAllocationRecipe? = null,
        /** Sessions to reduce/retire, by the funded share being moved out of each. */
        val sessionShareChanges: List<SessionShareChange> = emptyList(),
        /** Every placement superseded by this operation — the journal's replacement aliases. */
        val supersededPlacementIds: List<String> = emptyList(),
        /** The superseded recipe shape (or null), preserved for merge tombstone checks. */
        val replacedRecipeJson: String? = null,
        /** Signed edits to `daily_progress` for the affected old and new dates. */
        val dateCreditDeltas: List<DailyCreditDelta> = emptyList(),
        /** Stable evidence identity keying the journal operation; null for a no-op. */
        val evidenceIdentity: String? = null,
    ) {
        /** Total share minutes this plan keeps active at the new revision (recipe counts once). */
        val conservedNewMinutes: Long
            get() = sequenceOf(
                keptTimedShares.asSequence().map { it.minutes },
                newEvidencePlacements.asSequence().map { it.minutes.toLong() },
                keptAllocationRows.asSequence().map { it.minutes },
                newAllocationRows.asSequence().map { it.minutes },
                sequenceOf(newRecipeMinutes()),
            ).flatten().fold(0L, Math::addExact)

        private fun newRecipeMinutes(): Long = newRecipe?.let {
            Math.addExact(
                Math.multiplyExact(it.quotient ?: 0L, it.eligibleCount.toLong()),
                it.remainderCount ?: 0L,
            )
        } ?: 0L

        /** Dates whose raw daily totals this operation changes. */
        val affectedDates: Set<String> get() = dateCreditDeltas.map { it.date }.toSet()
    }

    /** What one reconciliation decided about a retained contribution. */
    enum class RecoveryReconcileOperation { UPGRADE, NO_CHANGE }

    /**
     * Pure computation of the next-revision placement graph. No Room call happens here; every id in
     * the output is the deterministic storage identity, so a replay of this exact plan collides at
     * insert and advances nothing twice.
     *
     * @param sessionMinutesById current total minutes of every session that may host a funded
     *   share, used only to decide a session's remaining minutes after its share moves out. It is
     *   never used to infer a contribution window.
     */
    fun computeContributionReplacement(
        contribution: PlaytimeContribution,
        activeTimed: List<TimedContributionPlacement>,
        activeAllocations: List<UnknownTimeAllocation>,
        evidence: List<PlaytimeTimingEvidence>,
        consumedSpans: List<ConsumedPlaytimeSpan>,
        activeReaderIdentity: CloudReaderIdentity?,
        now: Long,
        sessionMinutesById: Map<Long, Int> = emptyMap(),
        activeRecipe: UnknownTimeAllocationRecipe? = null,
    ): RecoveryPlacementReconcilePlan {
        fun noChange() = RecoveryPlacementReconcilePlan(
            contributionId = contribution.contributionId,
            account = contribution.account,
            appId = contribution.appId,
            operation = RecoveryReconcileOperation.NO_CHANGE,
            priorRevision = contribution.revision,
            currentRevision = contribution.revision,
        )

        val zone = contribution.zone
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: return noChange()
        val windowStart = contribution.windowStartAt ?: return noChange()
        if (contribution.windowEndAt <= windowStart) return noChange()
        if (activeRecipe != null && !activeRecipe.pending) {
            val recipeTotal = try {
                Math.addExact(
                    Math.multiplyExact(activeRecipe.quotient ?: 0L, activeRecipe.eligibleCount.toLong()),
                    activeRecipe.remainderCount ?: 0L,
                )
            } catch (_: ArithmeticException) {
                return noChange()
            }
            if (recipeTotal > activeRecipe.materializedMinutes) {
                // The untouched tail is logical quantity with no allocation rows yet. A revision
                // cannot safely replace a materialized prefix while that tail is still represented
                // by the old recipe; leave it estimated until bounded materialization proves the
                // complete graph is available to reconcile atomically.
                return noChange()
            }
        }
        if (contribution.toMinutes > Int.MAX_VALUE || contribution.fromMinutes > Int.MAX_VALUE) {
            // The shared pure planner is Int-bounded; a beyond-Int cumulative range is out of scope
            // for an automatic upgrade and is left untouched rather than half-moved.
            return noChange()
        }

        val unlockedAllocations = activeAllocations.filter { allocation ->
            !allocation.protected && allocation.method in UNLOCKED_ALLOCATION_METHODS
        }
        val unlockedSyntheticShares = activeTimed.filter { share ->
            !share.protected && share.evidenceQuality == RecoveryEvidenceQuality.ESTIMATE
        }
        val unlockedMinutes = (unlockedAllocations.asSequence().map { it.minutes } +
            unlockedSyntheticShares.asSequence().map { it.minutes })
            .fold(0L, Math::addExact)
        val gameEvidence = evidence.filter { it.appId == contribution.appId }
        if (unlockedMinutes <= 0L || unlockedMinutes > Int.MAX_VALUE.toLong() || gameEvidence.isEmpty()) {
            return noChange()
        }

        // The planner is always routed down the evidence-plus-residual path: delayed evidence can
        // never claim the ordinary short-gap session estimate (its publication timing is unknown),
        // and that branch would fabricate a boundary from observation instants rather than from
        // evidence spans.
        val plan = PlaytimeAttributionPlanner.plan(
            PlaytimeRecoveryRequest(
                account = contribution.account,
                appId = contribution.appId,
                baselineEpoch = contribution.baselineEpoch,
                authorizedDeltaMinutes = unlockedMinutes.toInt(),
                previousObservationAt = windowStart,
                recoveryObservedAt = contribution.windowEndAt,
                zone = zone,
                evaluationDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
                // The shared planner validates an existing identity's funded quantity exactly.
                // Here it is only a pure allocator for the *unlocked slice*, not authority over
                // the source contribution. Do not attach the full source identity to a partial
                // amount; the synthetic range is never persisted and the caller reattaches the
                // output to the retained contribution after conservation is checked.
                priorHighWaterMinutes = (contribution.toMinutes - unlockedMinutes).toInt(),
                evidence = gameEvidence,
                consumedSpans = consumedSpans,
                activeReaderIdentity = activeReaderIdentity,
                publicationDelayConflict = true,
            ),
        )

        // Idempotency gate: if the evidence places no whole minute on top of what already-funded
        // spans hold, a re-revision would only clone the same estimates. Stop before any write.
        if (plan.timedPlacements.isEmpty() || plan.pending != null) return noChange()

        val newRevision = try {
            Math.addExact(contribution.revision, 1L)
        } catch (_: ArithmeticException) {
            return noChange()
        }
        val evidenceIdentity = gameEvidence
            .sortedBy { it.evidenceId }
            .firstOrNull()?.evidenceId
            ?: return noChange()

        // Frozen credit-time eligibility for replacement shares: each new dated share inherits the
        // facts of every superseded estimate that touched the same date, so moving minutes preserves
        // their original daily quest/Focus eligibility (design.md Decision 7).
        val flagsByDate = mutableMapOf<String, Pair<Boolean, Boolean>>()
        fun markFlags(date: String?, hidden: Boolean, goal: Boolean) {
            val key = date ?: return
            val existing = flagsByDate[key] ?: (false to false)
            flagsByDate[key] = (existing.first || hidden) to (existing.second || goal)
        }
        unlockedAllocations.forEach { markFlags(it.date, it.hiddenAtCredit, it.goalAtCredit) }
        unlockedSyntheticShares.forEach { markFlags(it.creditedDate, it.hiddenAtCredit, it.goalAtCredit) }
        val fallbackFlags = activeRecipe?.let { it.hiddenAtCredit to it.goalAtCredit } ?: (false to false)

        // Locked shares are re-declared at the new revision because storage supersedes whole
        // revisions. Content, session link and dates are unchanged, so they contribute no daily
        // delta; the deterministic id (which embeds the revision) is what changes, and it must be
        // regenerated or the insert would collide with the row just superseded.
        val lockedTimed = activeTimed.filterNot { share -> unlockedSyntheticShares.any { it.placementId == share.placementId } }
        val keptTimedShares = lockedTimed.map { share ->
            share.copy(
                placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                    contribution.contributionId, newRevision,
                    share.evidenceIdentity, share.creditedDate, share.sessionId,
                ),
                revision = newRevision,
                createdAt = now,
            )
        }
        val lockedAllocations = activeAllocations.filterNot { allocation ->
            unlockedAllocations.any { it.placementId == allocation.placementId }
        }
        val keptAllocationRows = lockedAllocations.map { allocation ->
            allocation.copy(
                placementId = PlaytimeRecoveryIdentity.allocationPlacementId(
                    contribution.contributionId, newRevision, allocation.date,
                ),
                revision = newRevision,
                createdAt = now,
            )
        }

        val evidencePlacements = plan.timedPlacements.map { timed ->
            val hidden = flagsByDate[timed.date.toString()]?.first ?: fallbackFlags.first
            val goal = flagsByDate[timed.date.toString()]?.second ?: fallbackFlags.second
            NewEvidencePlacement(
                startAt = timed.startAt,
                endAt = timed.endAt,
                minutes = timed.minutes,
                creditedDate = timed.date.toString(),
                evidenceIdentity = timed.evidenceIds.firstOrNull() ?: evidenceIdentity,
                evidenceQuality = when (timed.source) {
                    PlaytimeEvidenceSource.LOCAL -> RecoveryEvidenceQuality.LOCAL
                    PlaytimeEvidenceSource.CLOUD -> RecoveryEvidenceQuality.CLOUD
                    PlaytimeEvidenceSource.ESTIMATE -> RecoveryEvidenceQuality.ESTIMATE
                },
                hiddenAtCredit = hidden,
                goalAtCredit = goal,
            )
        }

        val residual = plan.residual?.let { mergeRetainedRecipeExclusions(it, activeRecipe) }
        val keptAllocationDates = keptAllocationRows.mapNotNull { it.date }.toSet()
        val (newAllocationRows, newRecipe) = materializeResidual(
            contribution = contribution,
            newRevision = newRevision,
            residual = residual,
            flagsByDate = flagsByDate,
            fallbackFlags = fallbackFlags,
            keptAllocationDates = keptAllocationDates,
            now = now,
        )

        // Session share reduction (task 6.3): for each mapped synthetic session whose funded share
        // is being moved out, drop exactly that share and preserve every other funded or legacy
        // minute on the row. A row that drops to zero with no independent state is retired by the
        // caller; unprovable legacy minutes are never touched because they are part of `before`.
        val changes = mutableListOf<SessionShareChange>()
        unlockedSyntheticShares
            .groupBy { it.sessionId }
            .forEach { (sessionId, shares) ->
                if (sessionId == null) return@forEach
                val shareMinutesLong = shares.fold(0L) { sum, share -> Math.addExact(sum, share.minutes) }
                require(shareMinutesLong <= Int.MAX_VALUE.toLong()) { "funded share exceeds session integer range" }
                val shareMinutes = shareMinutesLong.toInt()
                val before = sessionMinutesById[sessionId]
                    ?: shareMinutes // no row known: the pure rule can still describe the share move
                require(shareMinutes <= before) {
                    "funded share $shareMinutes exceeds session $sessionId minutes $before"
                }
                changes += SessionShareChange(
                    sessionId = sessionId,
                    beforeMinutes = before,
                    afterMinutes = before - shareMinutes,
                )
            }

        // Signed daily deltas via the shared share-based vocabulary: superseded estimates leave
        // their old dates, replacement allocations and evidence sessions arrive on their new ones,
        // and hidden-at-credit shares contribute to neither side. Locked rows re-declared on the
        // same dates cancel to zero.
        val beforeShares = datedPlacementShares(unlockedSyntheticShares) +
            datedAllocationShares(unlockedAllocations)
        val afterShares = evidencePlacements.map { placement ->
            PlacementShare(
                date = placement.creditedDate,
                minutes = placement.minutes.toLong(),
                goalAtCredit = placement.goalAtCredit,
                hiddenAtCredit = placement.hiddenAtCredit,
            )
        } + datedAllocationShares(newAllocationRows)
        val dateCreditDeltas = placementShareDeltas(beforeShares, afterShares)

        val supersededIds = unlockedSyntheticShares.map { it.placementId } +
            unlockedAllocations.map { it.placementId }

        return RecoveryPlacementReconcilePlan(
            contributionId = contribution.contributionId,
            account = contribution.account,
            appId = contribution.appId,
            operation = RecoveryReconcileOperation.UPGRADE,
            priorRevision = contribution.revision,
            currentRevision = newRevision,
            keptTimedShares = keptTimedShares,
            newEvidencePlacements = evidencePlacements,
            keptAllocationRows = keptAllocationRows,
            newAllocationRows = newAllocationRows,
            newRecipe = newRecipe,
            sessionShareChanges = changes.sortedBy { it.sessionId },
            supersededPlacementIds = supersededIds,
            replacedRecipeJson = activeRecipe?.let(::recipeShapeJson),
            dateCreditDeltas = dateCreditDeltas,
            evidenceIdentity = evidenceIdentity,
        )
    }

    /**
     * Turns the residual recipe into dated storage rows for ordinary windows, or a compact recipe
     * row for long windows (bounded storage; zero-minute shares never appear).
     *
     * Dates that already carry a locked allocation row (a protected or known-targeted-date share)
     * are excluded from the residual, and the whole residual is re-spread exactly over the
     * remaining eligible dates — storage allows one allocation row per date per revision, so a
     * residual share must never collide with a kept row on the same date.
     */
    private fun materializeResidual(
        contribution: PlaytimeContribution,
        newRevision: Long,
        residual: ResidualDateRecipe?,
        flagsByDate: Map<String, Pair<Boolean, Boolean>>,
        fallbackFlags: Pair<Boolean, Boolean>,
        keptAllocationDates: Set<String>,
        now: Long,
    ): Pair<List<UnknownTimeAllocation>, UnknownTimeAllocationRecipe?> {
        if (residual == null || residual.totalMinutes <= 0) return emptyList<UnknownTimeAllocation>() to null

        fun rowFor(date: String, minutes: Long): UnknownTimeAllocation {
            val hidden = flagsByDate[date]?.first ?: fallbackFlags.first
            val goal = flagsByDate[date]?.second ?: fallbackFlags.second
            return UnknownTimeAllocation(
                placementId = PlaytimeRecoveryIdentity.allocationPlacementId(
                    contribution.contributionId, newRevision, date,
                ),
                contributionId = contribution.contributionId,
                account = contribution.account,
                appId = contribution.appId,
                revision = newRevision,
                date = date,
                minutes = minutes,
                method = residual.method.toStorageMethod(),
                zone = contribution.zone,
                windowStartAt = contribution.windowStartAt,
                windowEndAt = contribution.windowEndAt,
                pendingDate = false,
                hiddenAtCredit = hidden,
                goalAtCredit = goal,
                createdAt = now,
            )
        }

        fun expandedExclusionDates(): Set<String> = residual.excludedRanges
            .flatMap { range -> datesBetween(range.start, range.endInclusive) }
            .toSet()

        if (residual.windowDateCount <= RESIDUAL_MATERIALIZE_MAX_DAYS) {
            val locked = keptAllocationDates
            val eligible = windowDates(residual).filter { it !in expandedExclusionDates() && it !in locked }
            val spread = eligible.ifEmpty { windowDates(residual).filter { it !in locked } }
            val shares = UnknownTimeDistribution.distributeExactly(residual.totalMinutes.toLong(), spread)
            return shares.map { (date, minutes) -> rowFor(date, minutes) } to null
        }

        val locked = keptAllocationDates.mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            .filter { it in residual.firstDate..residual.lastDate }
            .filterNot { date -> residual.excludedRanges.any { date in it } }
        val eligibleCount = residual.eligibleDateCount - locked.size
        require(eligibleCount > 0) { "locked placements exhaust the residual recipe's eligible dates" }
        val quotient = residual.totalMinutes / eligibleCount
        val remainder = residual.totalMinutes % eligibleCount
        val excludedRanges = PlaytimeAllocationRecipes.normalizeRanges(
            residual.excludedRanges + locked.map { it..it },
        )
        val excludedCsv = excludedRanges.joinToString(",") { range ->
            if (range.start == range.endInclusive) range.start.toString()
            else "${range.start}..${range.endInclusive}"
        }.ifEmpty { null }
        return emptyList<UnknownTimeAllocation>() to UnknownTimeAllocationRecipe(
            contributionId = contribution.contributionId,
            account = contribution.account,
            appId = contribution.appId,
            revision = newRevision,
            method = residual.method.toStorageMethod(),
            quotient = quotient.toLong(),
            remainderCount = remainder.toLong(),
            dateStart = residual.firstDate.toString(),
            dateEnd = residual.lastDate.toString(),
            eligibleCount = eligibleCount,
            zone = contribution.zone,
            windowStartAt = contribution.windowStartAt,
            windowEndAt = contribution.windowEndAt,
            excludedDatesCsv = excludedCsv,
            pending = false,
            hiddenAtCredit = flagsByDate.values.any { it.first } || fallbackFlags.first,
            goalAtCredit = flagsByDate.values.any { it.second } || fallbackFlags.second,
            updatedAt = now,
        )
    }

    /** Preserve an earlier compact recipe's proven fully-resolved date exclusions on re-placement. */
    private fun mergeRetainedRecipeExclusions(
        residual: ResidualDateRecipe,
        recipe: UnknownTimeAllocationRecipe?,
    ): ResidualDateRecipe {
        if (recipe == null || recipe.pending || recipe.excludedDatesCsv.isNullOrBlank()) return residual
        val retained = parseRecoveryExcludedDateRanges(recipe.excludedDatesCsv)
        val clipped = retained.mapNotNull { range ->
            val first = maxOf(residual.firstDate, range.start)
            val last = minOf(residual.lastDate, range.endInclusive)
            if (last.isBefore(first)) null else first..last
        }
        val normalized = PlaytimeAllocationRecipes.normalizeRanges(residual.excludedRanges + clipped)
        val calendarCount = java.time.temporal.ChronoUnit.DAYS.between(
            residual.firstDate,
            residual.lastDate,
        ) + 1L
        val excludedCount = normalized.sumOf {
            java.time.temporal.ChronoUnit.DAYS.between(it.start, it.endInclusive) + 1L
        }
        val eligible = (calendarCount - excludedCount).toInt()
        require(eligible > 0) { "retained full-resolution exclusions exhaust the residual date window" }
        val quotient = residual.totalMinutes / eligible
        val remainder = residual.totalMinutes % eligible
        return residual.copy(
            eligibleDateCount = eligible,
            baseMinutes = quotient,
            remainderCount = remainder,
            excludedRanges = normalized,
        )
    }

    /** Bounded enumeration of the recipe's inclusive window dates. */
    private fun windowDates(residual: ResidualDateRecipe): List<String> {
        val dates = ArrayList<String>()
        var date = residual.firstDate
        while (!date.isAfter(residual.lastDate)) {
            dates += date.toString()
            date = date.plusDays(1)
        }
        return dates
    }

    private fun PlaytimeAllocationMethod.toStorageMethod(): String = when (this) {
        PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE -> RecoveryAllocationMethods.SINGLE_DAY
        PlaytimeAllocationMethod.KNOWN_TARGETED_DATE -> RecoveryAllocationMethods.MULTI_DAY_ESTIMATE
        PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
        PlaytimeAllocationMethod.EVIDENCE_INFORMED,
        -> RecoveryAllocationMethods.MULTI_DAY_ESTIMATE
        PlaytimeAllocationMethod.PENDING_DATE_UNKNOWN -> RecoveryAllocationMethods.PENDING_DATE_UNKNOWN
    }

    /** Compact opaque shape for the journal's replaced-recipe tombstone. */
    private fun recipeShapeJson(recipe: UnknownTimeAllocationRecipe): String =
        "{\"revision\":${recipe.revision},\"quotient\":${recipe.quotient}," +
            "\"remainder\":${recipe.remainderCount},\"eligible\":${recipe.eligibleCount}}"

    private fun datesBetween(start: java.time.LocalDate, endInclusive: java.time.LocalDate): List<String> {
        val dates = ArrayList<String>()
        var date = start
        while (!date.isAfter(endInclusive)) {
            dates += date.toString()
            date = date.plusDays(1)
        }
        return dates
    }
}

/** Strict parser for compact frozen exclusion dates/ranges; malformed tokens are never dropped. */
internal fun parseRecoveryExcludedDateRanges(csv: String): List<ClosedRange<java.time.LocalDate>> =
    csv.split(',').map { rawToken ->
        val token = rawToken.trim()
        require(token.isNotEmpty()) { "blank token in recovery exclusion list" }
        val separator = token.indexOf("..")
        require(separator < 0 || token.indexOf("..", startIndex = separator + 2) < 0) {
            "malformed recovery exclusion '$token'"
        }
        val first = java.time.LocalDate.parse(
            if (separator < 0) token else token.substring(0, separator).trim(),
        )
        val last = java.time.LocalDate.parse(
            if (separator < 0) token else token.substring(separator + 2).trim(),
        )
        require(!last.isBefore(first)) { "inverted recovery exclusion '$token'" }
        first..last
    }

// ------------------------------------------------------------------ applying service

/**
 * Applies a [RecoveryPlacementReconcilePlan] atomically through the existing raw coordinator and
 * Room transaction, then runs the staged corrective-then-earned derived finalization
 * ([RecoveryDerivedFinalizer]) so the corrected dates re-evaluate quests/streaks while per-game XP
 * stays unchanged (placement-only corrections carry no fresh earned inputs).
 *
 * The acquired-evidence hook for the parent's reader/worker wiring is [reconcileDelayedEvidence]:
 * it finds every retained contribution for a game that still has unlocked estimated shares and
 * reconciles them with the supplied validated evidence. Evidence identity, isolation and page
 * acquisition are deliberately left to the parent's reader path — this service only re-validates
 * evidence against the contribution window and the active reader fence inside the committing
 * transaction.
 */
@Singleton
class RecoveryPlacementReconcileUseCase @Inject constructor(
    private val recoveryDao: PlaytimeRecoveryDao,
    private val sessionDao: SessionDao,
    private val dailyCredit: RecoveryDailyCreditCoordinator,
    private val settings: SettingsRepository,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val syncCoordinator: com.example.backlogium.work.SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
    private val materializer: UnknownTimeMaterializer? = null,
) {

    /** What one reconciliation call did. */
    data class Outcome(
        val contributionId: String,
        val operation: RecoveryPlacementReconciler.RecoveryReconcileOperation,
        val priorRevision: Long,
        val currentRevision: Long,
        val movedToEvidenceMinutes: Long,
        val affectedDates: Set<String>,
    )

    /**
     * Parent wiring hook: reconcile every retained contribution of [appId] that the newly acquired
     * [evidence] can upgrade. Idempotent across duplicate pages, restarts and concurrent syncs.
     */
    suspend fun reconcileDelayedEvidence(
        account: String,
        appId: Long,
        evidence: List<PlaytimeTimingEvidence>,
        activeReaderIdentity: CloudReaderIdentity? = null,
    ): List<Outcome> {
        if (recoveryDao.activeAccount() != account) return emptyList()
        val contributions = recoveryDao.contributionsForGame(account, appId)
        if (contributions.isEmpty() || evidence.none { it.appId == appId }) return emptyList()
        return contributions
            .sortedBy { it.baselineEpoch }
            .map { contribution ->
                reconcileContribution(contribution, evidence, activeReaderIdentity)
            }
            .filterNot { it.operation == RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE }
    }

    /** Variant for a caller already holding the shared sync/account barrier (non-reentrant mutex). */
    suspend fun reconcileDelayedEvidenceWhileLocked(
        account: String,
        appId: Long,
        evidence: List<PlaytimeTimingEvidence>,
        activeReaderIdentity: CloudReaderIdentity? = null,
    ): List<Outcome> {
        if (recoveryDao.activeAccount() != account) return emptyList()
        val contributions = recoveryDao.contributionsForGame(account, appId)
        if (contributions.isEmpty() || evidence.none { it.appId == appId }) return emptyList()
        return contributions.sortedBy { it.baselineEpoch }
            .map { reconcileLocked(it, evidence, activeReaderIdentity) }
            .filterNot { it.operation == RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE }
    }

    /** Reconcile one retained contribution with validated evidence. */
    suspend fun reconcileContribution(
        contribution: PlaytimeContribution,
        evidence: List<PlaytimeTimingEvidence>,
        activeReaderIdentity: CloudReaderIdentity? = null,
    ): Outcome = syncCoordinator.withLock {
        if (recoveryDao.activeAccount() != contribution.account) {
            Outcome(contribution.contributionId, RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE,
                contribution.revision, contribution.revision, 0L, emptySet())
        } else {
            reconcileLocked(contribution, evidence, activeReaderIdentity)
        }
    }

    private suspend fun reconcileLocked(
        contribution: PlaytimeContribution,
        evidence: List<PlaytimeTimingEvidence>,
        activeReaderIdentity: CloudReaderIdentity?,
    ): Outcome {
        // Raw reads, planning, writes and conservation all live inside one Room transaction; the
        // staged derived finalization is the resumable tail that runs outside it (design §8).
        val rules = settings.ruleConfigWithVersion.first()
        val configVersion = rules.version
        val config = rules.config
        val evaluationDate = time.today()
        val prepared = transaction.run {
            if (recoveryDao.activeAccount() != contribution.account) return@run null
            val current = recoveryDao.contribution(contribution.contributionId) ?: return@run null

            // A prior attempt that committed the raw graph but died before derived finalization
            // must resume that same journal, not compute a fresh replacement (which would already
            // see the new revision and find nothing to move).
            val resumable = recoveryDao.journalsForContribution(current.contributionId)
                .firstOrNull { it.derivedStatus in RESUMABLE_STATUSES }
            if (resumable != null) {
                return@run Prepared(
                    inputs = RecoveryDerivedFinalizeInputs.fromJournal(resumable),
                    movedToEvidenceMinutes = recoveryDao.sumActiveTimedMinutes(current.contributionId),
                    affectedDates = resumable.affectedDatesCsv.split(",")
                        .filter { it.isNotBlank() }.toSet(),
                )
            }

            val activeTimed = recoveryDao.activeTimedPlacements(current.contributionId)
            val activeAllocations = recoveryDao.activeAllocations(current.contributionId)
            val activeRecipe = recoveryDao.recipe(current.contributionId)
            val sessionMinutesById = sessionDao.getAll().associate { it.id to it.minutes }
            val plan = RecoveryPlacementReconciler.computeContributionReplacement(
                contribution = current,
                activeTimed = activeTimed,
                activeAllocations = activeAllocations,
                evidence = evidence,
                consumedSpans = computeConsumedSpans(current),
                activeReaderIdentity = activeReaderIdentity,
                now = time.nowMillis(),
                sessionMinutesById = sessionMinutesById,
                activeRecipe = activeRecipe,
            )
            if (plan.operation == RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE) {
                // Already-upgraded, fully-consumed or nothing unlockable: a replay is a no-op.
                return@run null
            }

            val operationId = PlaytimeRecoveryIdentity.journalOperationId(
                current.contributionId, plan.priorRevision, plan.evidenceIdentity.orEmpty(),
            )
            val inputs = RecoveryDerivedFinalizeInputs(
                kind = RecoveryFinalizeKind.CORRECTIVE_ONLY,
                operationId = operationId,
                account = current.account,
                evaluationDate = evaluationDate,
                configVersion = configVersion,
                config = config,
                correctiveContributions = setOf(
                    RecoveryContributionSnapshot(
                        account = current.account,
                        appId = current.appId,
                        baselineEpoch = current.baselineEpoch,
                        fromMinutes = current.fromMinutes,
                        toMinutes = current.toMinutes,
                        revision = plan.currentRevision,
                        authorizedMinutes = current.authorizedMinutes,
                    ),
                ),
                earned = RecoveryEarnedInputs(),
            )
            if (recoveryDao.journal(operationId) == null) {
                commitRawGraph(current, plan, inputs)
                verifyConservation(current.contributionId)
            }
            Prepared(
                inputs = inputs,
                movedToEvidenceMinutes = plan.newEvidencePlacements.sumOf { it.minutes.toLong() },
                affectedDates = plan.affectedDates,
            )
        }

        if (prepared == null) {
            return Outcome(
                contributionId = contribution.contributionId,
                operation = RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE,
                priorRevision = contribution.revision,
                currentRevision = contribution.revision,
                movedToEvidenceMinutes = 0L,
                affectedDates = emptySet(),
            )
        }

        // The staged corrective/earned pass takes the derived-write lock itself; a placement-only
        // correction carries no fresh earned inputs, so it re-persists the same canonical totals
        // under corrective provenance and no new earned event is produced.
        val finalizedAt = time.nowMillis()
        // A compact replacement recipe is the canonical dated input, but daily quest rows are
        // materialized in bounded slices. Complete its slices before the corrective persist reads
        // daily progress; an interrupted run remains recoverable from the same durable cursor.
        materializer?.materializeAll(prepared.inputs.account, finalizedAt)
        finalizer.finalize(prepared.inputs, finalizedAt)
        val journal = recoveryDao.journal(prepared.inputs.operationId)
        return Outcome(
            contributionId = contribution.contributionId,
            operation = RecoveryPlacementReconciler.RecoveryReconcileOperation.UPGRADE,
            priorRevision = journal?.priorRevision ?: contribution.revision,
            currentRevision = journal?.currentRevision ?: contribution.revision,
            movedToEvidenceMinutes = prepared.movedToEvidenceMinutes,
            affectedDates = prepared.affectedDates,
        )
    }

    private data class Prepared(
        val inputs: RecoveryDerivedFinalizeInputs,
        val movedToEvidenceMinutes: Long,
        val affectedDates: Set<String>,
    )

    /**
     * The staged derived finalization for recovery corrections. The corrective pass excludes this
     * operation's fresh earned inputs (none for placement-only corrections); the earned pass
     * includes them under SYNC. Both delegate to the single on-device author, [GamificationUpdater],
     * under the operation's captured rule config (a later settings change cannot silently re-base
     * the corrective pass).
     */
    private val finalizer: RecoveryDerivedFinalizer by lazy {
        RecoveryDerivedFinalizer(
            recoveryDao = recoveryDao,
            derivedStateWrites = derivedStateWrites,
            compute = GamificationRecoveryDerivedCompute(gamificationUpdater),
        )
    }

    private companion object {
        val RESUMABLE_STATUSES = setOf(
            RecoveryJournalStatus.RAW_COMMITTED,
            RecoveryJournalStatus.CORRECTIVE_DONE,
            RecoveryJournalStatus.EARNED_DONE,
        )
    }

    /** One atomic raw commit: sessions, placements, supersedes, daily deltas, journal, revision. */
    private suspend fun commitRawGraph(
        contribution: PlaytimeContribution,
        plan: RecoveryPlacementReconciler.RecoveryPlacementReconcilePlan,
        inputs: RecoveryDerivedFinalizeInputs,
    ) {
        val now = time.nowMillis()
        // 1. Insert the evidence-informed sessions first so the funded shares can link to them.
        val sessionIds = plan.newEvidencePlacements.map { placement ->
            sessionDao.insert(
                Session(
                    appId = contribution.appId,
                    startAt = placement.startAt,
                    endAt = placement.endAt,
                    minutes = placement.minutes,
                    open = false,
                    recoveredSharedPlay = RecoveredSharedPlayState.NONE,
                    timingInformedSteamPlay = TimingInformedSteamPlayState.FULL,
                ),
            )
        }
        val evidenceShares = plan.newEvidencePlacements.zip(sessionIds).map { (placement, sessionId) ->
            TimedContributionPlacement(
                placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                    contribution.contributionId, plan.currentRevision,
                    placement.evidenceIdentity, placement.creditedDate, sessionId,
                ),
                contributionId = contribution.contributionId,
                account = contribution.account,
                appId = contribution.appId,
                revision = plan.currentRevision,
                sessionId = sessionId,
                minutes = placement.minutes.toLong(),
                creditedDate = placement.creditedDate,
                zone = contribution.zone.orEmpty(),
                evidenceIdentity = placement.evidenceIdentity,
                evidenceQuality = placement.evidenceQuality,
                hiddenAtCredit = placement.hiddenAtCredit,
                goalAtCredit = placement.goalAtCredit,
                createdAt = now,
            )
        }

        // 2. Supersede the whole previous revision, then rewrite the graph at the new revision.
        recoveryDao.supersedeTimedPlacementsUpTo(contribution.contributionId, plan.currentRevision)
        recoveryDao.supersedeAllocationsUpTo(contribution.contributionId, plan.currentRevision)
        recoveryDao.insertTimedPlacements(plan.keptTimedShares + evidenceShares)
        if (plan.newRecipe != null) {
            // A recipe aggregates the whole residual: it must not coexist with dated rows, which
            // conservation would then count twice (or drop). The pure planner only proposes one
            // for windows whose residual had no dated rows.
            check(plan.keptAllocationRows.isEmpty() && plan.newAllocationRows.isEmpty()) {
                "recipe-backed reconciliation cannot coexist with dated allocation rows"
            }
            recoveryDao.upsertRecipe(plan.newRecipe)
        } else {
            recoveryDao.insertAllocations(plan.keptAllocationRows + plan.newAllocationRows)
        }

        // 3. Reduce or retire the mapped synthetic sessions by exactly the moved funded share,
        // preserving every other funded or legacy minute on the row. A retired row is only one
        // whose minutes were entirely the superseded share (no independent state references it).
        val sessionsById = sessionDao.getAll().associateBy { it.id }
        plan.sessionShareChanges.forEach { change ->
            val after = change.afterMinutes.coerceAtLeast(0)
            if (after <= 0) {
                sessionDao.deleteById(change.sessionId)
            } else {
                val stored = sessionsById[change.sessionId] ?: return@forEach
                sessionDao.update(stored.copy(minutes = after))
            }
        }

        // 4. Old/new dated credit: the share-based coordinator moves exactly the operation's own
        // shares off the old dates and onto the new ones, preserving unrelated row remainder and
        // rejecting underflow (inside the caller's transaction, so nothing partial is written).
        dailyCredit.apply(plan.dateCreditDeltas)

        // 5. Advance the revision and journal the operation durably, before derived work.
        recoveryDao.advanceRevision(contribution.contributionId, plan.currentRevision)
        recoveryDao.upsertJournal(
            PlaytimeRecoveryJournal(
                operationId = inputs.operationId,
                contributionId = contribution.contributionId,
                account = contribution.account,
                appId = contribution.appId,
                priorRevision = plan.priorRevision,
                currentRevision = plan.currentRevision,
                affectedDatesCsv = plan.affectedDates.sorted().joinToString(","),
                replacedPlacementIdsCsv = plan.supersededPlacementIds.sorted().joinToString(",").ifEmpty { null },
                replacedRecipeJson = plan.replacedRecipeJson,
                derivedStatus = RecoveryJournalStatus.RAW_COMMITTED,
                payloadJson = RecoveryDerivedFinalizeInputs.encode(inputs),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun verifyConservation(contributionId: String) {
        check(recoveryDao.validateGraph(contributionId)) {
            "recovery graph for $contributionId violates conservation after reconciliation"
        }
    }

    /**
     * Spans already funded and therefore unavailable to new evidence: this contribution's locked
     * evidence shares and every other contribution's funded shares for the same game. Unlocked
     * estimates of this contribution are intentionally omitted, because the new evidence is
     * replacing those shares, not competing with them. Prefer the structured funded interval over
     * a potentially wider host session; only legacy rows without it fall back to the host bounds.
     */
    private suspend fun computeConsumedSpans(contribution: PlaytimeContribution): List<ConsumedPlaytimeSpan> {
        val sessionsById = sessionDao.getAll().associateBy { it.id }
        val spans = mutableListOf<ConsumedPlaytimeSpan>()
        val allContributions = recoveryDao.contributionsForGame(contribution.account, contribution.appId)
        for (candidate in allContributions) {
            val shares = recoveryDao.activeTimedPlacements(candidate.contributionId)
            for (share in shares) {
                if (candidate.contributionId == contribution.contributionId &&
                    !share.protected && share.evidenceQuality == RecoveryEvidenceQuality.ESTIMATE
                ) continue
                val storedSpan = evidenceSpanFrom(share.evidenceIdentity)
                if (storedSpan != null && storedSpan.second > storedSpan.first) {
                    spans += ConsumedPlaytimeSpan(storedSpan.first, storedSpan.second)
                    continue
                }
                val session = share.sessionId?.let { sessionsById[it] } ?: continue
                val endAt = session.endAt
                if (endAt == null || endAt <= session.startAt) continue
                spans += ConsumedPlaytimeSpan(session.startAt, endAt)
            }
        }
        return spans
    }

    /** Structured timing facts end with `start|end`; legacy cloud facts use `cloud:app:start:end`. */
    private fun evidenceSpanFrom(identity: String?): Pair<Long, Long>? {
        if (identity.isNullOrBlank()) return null
        val fields = identity.split('|')
        if (fields.size >= 3) {
            val start = fields[fields.lastIndex - 1].toLongOrNull()
            val end = fields.last().toLongOrNull()
            if (start != null && end != null && end > start) return start to end
        }
        val cloud = identity.split(':')
        if (cloud.size >= 4 && cloud[0] == "cloud") {
            val start = cloud[2].toLongOrNull()
            val end = cloud[3].toLongOrNull()
            if (start != null && end != null && end > start) return start to end
        }
        return null
    }
}
