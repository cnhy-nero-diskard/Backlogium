package com.example.backlogium.data.backup

import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity

/**
 * A pure, validated plan for merging an imported recovery section into local recovery state
 * (recover-missed-playtime, design.md Decision 10). No Room, Android or coroutine types appear
 * here: the planner reasons only about [BackupRecovery] DTOs, so every merge rule is testable on
 * the JVM and the same plan drives both a manual import and an automatic restore.
 *
 * Quantity is normalised **per funded segment**, not per overlap cluster: cumulative ranges are
 * split at every breakpoint, and each `(lo, hi]` segment is assigned exactly one funding/placement
 * source by precedence (protected local placement > validated timing > newer consistent estimate
 * revision). Segments that only one side covers keep that side's provenance unchanged; the union
 * is therefore always conserved and never summed twice. A side that is partly displaced yields
 * deterministic fragments whose placements are split exactly.
 */
data class RecoveryMergePlan(
    /** Observations to write, already resolved against the newer coherent local baseline. */
    val observations: List<BackupRecoveryObservation>,
    /** Every final contribution graph for [affectedAccounts]. */
    val contributions: List<RecoveryContributionGraph>,
    /** Accounts whose contribution graph changes and must be rewritten atomically. */
    val affectedAccounts: List<String>,
    /** Replacement aliases/tombstones to persist so an older backup cannot resurrect them. */
    val replacements: List<BackupReplacement>,
    /** Ambiguities retained locally with an explicit report instead of an unsafe merge. */
    val conflicts: List<RecoveryMergeConflict>,
    /** Non-fatal reports, chiefly foreign baselines kept inactive. */
    val warnings: List<RecoveryMergeWarning>,
) {
    /** True when nothing about the funded graph changes — a repeated import is genuinely a no-op. */
    val isNoOp: Boolean get() = affectedAccounts.isEmpty() && observations.isEmpty() &&
        replacements.isEmpty()
}

/** One funded contribution and its complete placement graph. */
data class RecoveryContributionGraph(
    val contribution: BackupContribution,
    val timedPlacements: List<BackupTimedPlacement>,
    val unknownAllocations: List<BackupUnknownAllocation>,
    val recipe: BackupAllocationRecipe?,
    /**
     * Superseded placement ids inherited from displaced sources in the same range group. The
     * writer attaches them to the replacement journal so the tombstones outlive the removed rows
     * as replacement aliases — funded quantity is never deleted, only re-aliased.
     */
    val carriedTombstonePlacementIds: List<String> = emptyList(),
    /**
     * Replacement journals of displaced local contributions (including unfinished derived
     * finalization stages and their payloads) re-targeted onto the graph that displaced them.
     */
    val carriedJournals: List<BackupReplacement> = emptyList(),
)

data class RecoveryMergeConflict(
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    val detail: String,
)

data class RecoveryMergeWarning(val account: String, val detail: String)

/**
 * The outcome summary the merge emits for the import result channel (tasks 8.6): the recovery
 * resolutions that were surfaced rather than silently applied, plus what was written. `isClean`
 * is false when an ambiguity was retained locally and the user should see the report.
 */
data class RecoveryMergeSummary(
    val conflicts: List<RecoveryMergeConflict>,
    val warnings: List<RecoveryMergeWarning>,
    val importedObservations: Int,
    val importedContributions: Int,
) {
    val isClean: Boolean get() = conflicts.isEmpty()
}

object RecoveryBackupMergePlanner {

    /**
     * @param local the receiving installation's recovery section (null/empty on a fresh install).
     * @param imported the file's recovery section (null for a legacy v1/v2 file).
     * @param activeAccount the configured SteamID64; observations for any other account stay
     *   inactive by never being written as usable detector state.
     */
    fun plan(
        local: BackupRecovery?,
        imported: BackupRecovery?,
        activeAccount: String?,
    ): RecoveryMergePlan {
        val localObs = local?.observations.orEmpty()
        val importedObs = imported?.observations.orEmpty()
        val warnings = mutableListOf<RecoveryMergeWarning>()
        val observations = resolveObservations(localObs, importedObs, activeAccount, warnings)

        val localReplacements = local?.replacements.orEmpty()
        val importedReplacements = imported?.replacements.orEmpty()
        val tombstones = placementTombstones(localReplacements + importedReplacements)

        val localSources = contributionGraphs(local)
        val importedSources = contributionGraphs(imported)

        // A legacy import cannot be proven disjoint from locally funded recovery, so the local
        // detector state is retained and the ambiguity is reported rather than silently accepted.
        if (imported == null && localSources.isNotEmpty()) {
            warnings += RecoveryMergeWarning(
                localSources.first().contribution.account,
                "legacy backup without recovery sections merged over locally funded recovery; " +
                    "local quantity retained and no imported activity assumed additionally funded",
            )
        }

        val conflicts = mutableListOf<RecoveryMergeConflict>()
        val groupedLocal = localSources.groupBy { key(it) }
        val groupedImported = importedSources.groupBy { key(it) }
        val keys = (groupedLocal.keys + groupedImported.keys).distinct()

        val finalByAccount = mutableMapOf<String, MutableList<RecoveryContributionGraph>>()
        keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })).forEach { key ->
            val localHere = groupedLocal[key].orEmpty()
            val importedHere = groupedImported[key].orEmpty()
            val sourced = localHere.map { Sourced(imported = false, graph = it) } +
                importedHere.map { Sourced(imported = true, graph = it) }
            finalByAccount.getOrPut(key.first) { mutableListOf() } +=
                resolveSegments(key, sourced, tombstones, localReplacements, conflicts)
        }

        val localByAccount = localSources.groupBy { it.contribution.account }
        val affected = finalByAccount.filter { (account, final) ->
            final.toSet() != localByAccount[account].orEmpty().toSet()
        }

        val finalGraphs = affected.values.flatten()
        val survivingIds = finalGraphs.map { it.contribution.contributionId }.toSet()
        // A legacy import (no recovery section) or a repeat that changes nothing must be a true
        // no-op. Unfinished finalization journals survive through their payload, which the graph's
        // carriedJournals and the surviving replacements below preserve.
        val replacements = if (affected.isEmpty() && observations.isEmpty()) {
            emptyList()
        } else {
            (localReplacements + importedReplacements)
                .filter { it.contributionId in survivingIds }
                .distinctBy { it.operationId }
        }

        return RecoveryMergePlan(
            observations = observations,
            contributions = finalGraphs,
            affectedAccounts = affected.keys.sorted(),
            replacements = replacements,
            conflicts = conflicts,
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------ observations

    private fun resolveObservations(
        local: List<BackupRecoveryObservation>,
        imported: List<BackupRecoveryObservation>,
        activeAccount: String?,
        warnings: MutableList<RecoveryMergeWarning>,
    ): List<BackupRecoveryObservation> {
        val localByKey = local.associateBy { it.account to it.appId }
        val resolved = mutableListOf<BackupRecoveryObservation>()
        imported.forEach { incoming ->
            if (activeAccount == null || incoming.account != activeAccount) {
                warnings += RecoveryMergeWarning(
                    incoming.account,
                    "foreign recovery baseline for app ${incoming.appId} kept inactive",
                )
                return@forEach
            }
            val existing = localByKey[incoming.account to incoming.appId]
            val winner = newerCoherentObservation(existing, incoming)
            if (winner != existing) resolved += winner
        }
        return resolved
    }

    /**
     * Same-account baseline resolution keeps the **higher high-water as the quantity anchor no
     * matter which side is newer**: a newer local timestamp with a higher imported high-water must
     * not ignore that higher anchor, or a future Steam read would re-credit already-imported
     * quantity. The observation instants/epoch come from the newer side; the high-water is the max.
     */
    private fun newerCoherentObservation(
        local: BackupRecoveryObservation?,
        imported: BackupRecoveryObservation,
    ): BackupRecoveryObservation {
        if (local == null) return imported
        val localAt = local.lastObservationAt?.epochMilliOrNull()
        val importedAt = imported.lastObservationAt?.epochMilliOrNull()
        val highWater = maxOf(local.highWaterMinutes, imported.highWaterMinutes)
        val localNewer = (localAt ?: Long.MIN_VALUE) >= (importedAt ?: Long.MIN_VALUE)
        return if (localNewer) {
            if (highWater == local.highWaterMinutes) local
            else local.copy(highWaterMinutes = highWater)
        } else {
            if (importedAt == null) local else imported.copy(highWaterMinutes = highWater)
        }
    }

    // ------------------------------------------------------------------ segments

    private fun resolveSegments(
        key: Triple<String, Long, Long>,
        sources: List<Sourced>,
        tombstones: Set<String>,
        localReplacements: List<BackupReplacement>,
        conflicts: MutableList<RecoveryMergeConflict>,
    ): List<RecoveryContributionGraph> {
        // Retired placements are already replacement aliases, not evidence that may win a range.
        // Apply tombstones before precedence: otherwise an older high-quality timed row could win,
        // only be converted to an estimate afterward, and roll the surviving revision behind its
        // replacement journal (or replace a newer live graph with stale placement facts).
        val sorted = sources
            .map { source -> source.copy(graph = filterTombstoned(source.graph, tombstones)) }
            .sortedWith(compareBy({ it.graph.contribution.fromMinutes }, { it.graph.contribution.toMinutes }))
        if (sorted.isEmpty()) return emptyList()
        val breakpoints = sorted
            .flatMap { listOf(it.graph.contribution.fromMinutes, it.graph.contribution.toMinutes) }
            .distinct()
            .sorted()
        if (breakpoints.size < 2) return emptyList()

        data class Segment(val lo: Long, val hi: Long)

        val segments = breakpoints.zipWithNext().map { (lo, hi) -> Segment(lo, hi) }
        val resolved = segments.mapNotNull { segment ->
            val covering = sorted.filter {
                it.graph.contribution.fromMinutes <= segment.lo && it.graph.contribution.toMinutes >= segment.hi
            }
            val winner = covering.minWithOrNull(WINNER_COMPARATOR) ?: return@mapNotNull null
            val bestTier = quality(winner.graph)
            val bestRevision = winner.graph.contribution.revision
            val equivalentWinners = covering.filter {
                quality(it.graph) == bestTier && it.graph.contribution.revision == bestRevision
            }
            if (equivalentWinners.size > 1 &&
                equivalentWinners.map { placementFingerprint(it.graph) }.distinct().size > 1
            ) {
                conflicts += RecoveryMergeConflict(
                    account = key.first,
                    appId = key.second,
                    baselineEpoch = key.third,
                    detail = "ambiguous equal-precedence placements for cumulative segment " +
                        "(${segment.lo}, ${segment.hi}]; deterministic source precedence retained one graph",
                )
            }
            segment to winner
        }

        // Group consecutive segments under the same winning source into runs.
        val runs = mutableListOf<MutableList<Pair<Segment, Sourced>>>()
        resolved.forEach { (segment, winner) ->
            val last = runs.lastOrNull()
            if (last != null && last.last().second.graph.contribution.contributionId == winner.graph.contribution.contributionId) {
                last += segment to winner
            } else {
                runs += mutableListOf(segment to winner)
            }
        }

        data class Run(
            val winner: RecoveryContributionGraph,
            val lo: Long,
            val hi: Long,
            val fullRange: Boolean,
        )

        val plannedRuns = runs.map { run ->
            val winner = run.first().second.graph
            val lo = run.first().first.lo
            val hi = run.last().first.hi
            val fullRange = winner.contribution.fromMinutes == lo && winner.contribution.toMinutes == hi
            Run(winner, lo, hi, fullRange)
        }

        // Local sources that do not survive as a whole graph were displaced by the imported
        // winner. Their tombstones and unfinished-finalization journals are carried forward so
        // nothing is silently deleted: quantity is re-aliased, stages stay resumable.
        val localSourceIds = sources.filterNot { it.imported }.map { it.graph.contribution.contributionId }.toSet()
        val keptIds = plannedRuns.map { run -> (if (run.fullRange) run.winner.contribution.contributionId else fragmentId(run.winner, run.lo, run.hi)) }.toSet()
        val displacedLocalIds = localSourceIds.filterNot { it in keptIds }
        val carriedTombstones = tombstones.filter { tomb -> displacedLocalIds.any { id -> tomb.startsWith("$id:") } }
        val displacedJournals = localReplacements.filter { it.contributionId in displacedLocalIds }

        return plannedRuns.mapIndexed { index, run ->
            val journals = if (index == 0 && displacedJournals.isNotEmpty()) {
                displacedJournals.map { replacement ->
                    val destination = if (run.fullRange) run.winner.contribution.contributionId else fragmentId(run.winner, run.lo, run.hi)
                    if (destination == replacement.contributionId) replacement
                    else replacement.copy(
                        contributionId = destination,
                        operationId = "${replacement.operationId}|>$destination",
                        priorRevision = null,
                        currentRevision = run.winner.contribution.revision,
                    )
                }
            } else {
                emptyList()
            }
            materializeRun(run.winner, run.lo, run.hi, run.fullRange, carriedTombstones, journals, tombstones)
        }
    }

    private fun materializeRun(
        winner: RecoveryContributionGraph,
        lo: Long,
        hi: Long,
        fullRange: Boolean,
        carriedTombstones: List<String>,
        carriedJournals: List<BackupReplacement>,
        tombstones: Set<String>,
    ): RecoveryContributionGraph {
        if (fullRange) {
            // Whole source is the owner of exactly its own range: keep identity and placements.
            // Tombstoned imported items are re-aliased so no superseded placement id returns while
            // funded quantity is preserved (design #7).
            val kept = filterTombstoned(winner, tombstones)
            return kept.copy(
                carriedTombstonePlacementIds = carriedTombstones,
                carriedJournals = carriedJournals,
            )
        }
        val fragmentContribution = winner.contribution.copy(
            contributionId = fragmentId(winner, lo, hi),
            fromMinutes = lo,
            toMinutes = hi,
            authorizedMinutes = hi - lo,
        )
        val recipe = winner.recipe
        if (recipe != null && !recipe.pending) {
            val recipeTotal = Math.addExact(
                Math.multiplyExact(recipe.quotient ?: 0L, recipe.eligibleCount.toLong()),
                recipe.remainderCount ?: 0L,
            )
            val timedShares = splitWeighted(
                hi - lo,
                winner.timedPlacements.map { it.minutes } + recipeTotal,
                winner.contribution.authorizedMinutes,
            )
            val timed = winner.timedPlacements.mapIndexedNotNull { index, placement ->
                val minutes = timedShares[index]
                if (minutes <= 0L) null
                else placement.copy(
                    placementId = splitTimedId(fragmentContribution.contributionId, "t", index),
                    contributionId = fragmentContribution.contributionId,
                    revision = fragmentContribution.revision,
                    minutes = minutes,
                    superseded = false,
                )
            }
            val recipeMinutes = timedShares.last()
            val fragmentRecipe = if (recipeMinutes > 0L) recipe.copy(
                contributionId = fragmentContribution.contributionId,
                revision = fragmentContribution.revision,
                quotient = recipeMinutes / recipe.eligibleCount,
                remainderCount = recipeMinutes % recipe.eligibleCount,
                materializedMinutes = 0L,
                materializedThroughDate = null,
            ) else null
            return RecoveryContributionGraph(
                contribution = fragmentContribution,
                timedPlacements = timed,
                unknownAllocations = emptyList(),
                recipe = fragmentRecipe,
                carriedTombstonePlacementIds = carriedTombstones,
                carriedJournals = carriedJournals,
            )
        }
        val split = splitPlacements(fragmentContribution, winner, hi - lo)
        return RecoveryContributionGraph(
            contribution = fragmentContribution,
            timedPlacements = split.first,
            unknownAllocations = split.second,
            recipe = null,
            carriedTombstonePlacementIds = carriedTombstones,
            carriedJournals = carriedJournals,
        )
    }

    /**
     * Deterministic, exact split of a winner's placement items to the fragment width `W = hi - lo`.
     * Each item receives a largest-remainder share of W over the winner's authorized total
     * (wide-safe BigInteger arithmetic under [BackupValidator]'s plausibility bounds), so the
     * fragment's placements sum exactly to W and no item gains minutes it did not own. Split items
     * receive new derivation-stable ids keyed to the *fragment*, which is precisely the
     * "replacement alias" behaviour a tombstone demands: the old placement id never returns, the
     * funded quantity survives, and two fragments of the same source can never collide.
     */
    private fun splitPlacements(
        fragment: BackupContribution,
        winner: RecoveryContributionGraph,
        width: Long,
    ): Pair<List<BackupTimedPlacement>, List<BackupUnknownAllocation>> {
        val total = winner.contribution.authorizedMinutes
        require(width in 1..total) { "fragment width $width outside funded range 1..$total" }
        val timed = winner.timedPlacements.sortedWith(compareBy({ it.creditedDate }, { it.placementId }))
        val dated = winner.unknownAllocations
            .filterNot { it.pendingDate }
            .sortedWith(compareBy({ it.date }, { it.placementId }))
        val pending = winner.unknownAllocations.filter { it.pendingDate }

        var accounted = 0L
        var remaining = width
        val outTimed = mutableListOf<BackupTimedPlacement>()
        val outAllocations = mutableListOf<BackupUnknownAllocation>()
        val index = IntArray(1)

        fun shareOf(size: Long): Long {
            val next = minOf(total, accounted + size)
            val share = exactShare(width, next, total) - exactShare(width, accounted, total)
            accounted = next
            remaining -= share
            return share
        }

        timed.forEach { item ->
            val share = shareOf(item.minutes)
            if (share > 0L) {
                outTimed += item.copy(
                    placementId = splitTimedId(fragment.contributionId, "t", index[0]++),
                    contributionId = fragment.contributionId,
                    revision = fragment.revision,
                    minutes = share,
                )
            }
        }
        dated.forEach { item ->
            val share = shareOf(item.minutes)
            if (share > 0L) {
                outAllocations += item.copy(
                    placementId = splitTimedId(fragment.contributionId, "u", index[0]++),
                    contributionId = fragment.contributionId,
                    revision = fragment.revision,
                    minutes = share,
                )
            }
        }
        pending.forEach { item ->
            val share = shareOf(item.minutes)
            if (share > 0L) {
                outAllocations += item.copy(
                    placementId = splitTimedId(fragment.contributionId, "p", index[0]++),
                    contributionId = fragment.contributionId,
                    revision = fragment.revision,
                    minutes = share,
                )
            }
        }
        if (remaining > 0L) {
            // The winner carried no placement rows (an unplaced funded contribution being split):
            // its fragment still must not lose quantity, so the slice is pending until a window
            // exists to date it (design.md Decision 5).
            outAllocations += BackupUnknownAllocation(
                placementId = splitTimedId(fragment.contributionId, "z", 0),
                contributionId = fragment.contributionId,
                account = fragment.account,
                appId = fragment.appId,
                revision = fragment.revision,
                date = null,
                minutes = remaining,
                method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN,
                zone = fragment.zone,
                windowStartAt = fragment.windowStartAt,
                windowEndAt = fragment.windowEndAt,
                pendingDate = true,
                createdAt = fragment.createdAt,
            )
            remaining = 0L
        }
        check(remaining == 0L) { "fragment split left $remaining unassigned" }
        return outTimed to outAllocations
    }

    /** `floor(width * accounted / total)`, computed without intermediate Long overflow. */
    private fun exactShare(width: Long, accounted: Long, total: Long): Long =
        (width.toBigInteger() * accounted.toBigInteger() / total.toBigInteger()).toLong()

    /** Deterministic largest-remainder split used when a funded range is cut into segments. */
    private fun splitWeighted(width: Long, weights: List<Long>, total: Long): List<Long> {
        require(width >= 0L && total > 0L && weights.all { it >= 0L })
        require(weights.fold(0L, Math::addExact) == total) { "placement weights do not equal source range" }
        val denominator = total.toBigInteger()
        val exact = weights.map { width.toBigInteger() * it.toBigInteger() }
        val shares = exact.map { it.divide(denominator).toLong() }.toMutableList()
        var remaining = width - shares.fold(0L, Math::addExact)
        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it].remainder(denominator) }.thenBy { it },
        )
        for (index in order) {
            if (remaining == 0L) break
            shares[index] = Math.addExact(shares[index], 1L)
            remaining--
        }
        check(remaining == 0L && shares.fold(0L, Math::addExact) == width)
        return shares
    }

    private fun splitTimedId(base: String, kind: String, index: Int): String = "$base:$kind:split:$index"

    /**
     * Re-aliases tombstoned active items of an imported graph instead of keeping them active: the
     * item's minutes become a compensating allocation so authorized quantity is conserved and no
     * superseded placement id (with its old session link) can resurrect.
     */
    private fun filterTombstoned(graph: RecoveryContributionGraph, tombstones: Set<String>): RecoveryContributionGraph {
        if (tombstones.isEmpty()) return graph
        val timed = graph.timedPlacements.filterNot { it.placementId in tombstones }
        val datedKept = graph.unknownAllocations.filterNot { it.placementId in tombstones }
        val removed = graph.timedPlacements.count { it.placementId in tombstones } +
            graph.unknownAllocations.count { it.placementId in tombstones }
        if (removed == 0) return graph
        val removedMinutes = graph.timedPlacements.filter { it.placementId in tombstones }.sumOf { it.minutes } +
            graph.unknownAllocations.filter { it.placementId in tombstones }.sumOf { it.minutes }
        val recipe = graph.recipe
        if (recipe != null && !recipe.pending) {
            val removedTimedMinutes = graph.timedPlacements
                .filter { it.placementId in tombstones }
                .sumOf { it.minutes }
            if (removedTimedMinutes == 0L) {
                // Dated rows are recipe slices, not a second quantity source. Re-alias their
                // materialized identities without changing the frozen quotient/remainder graph.
                val reAliased = datedKept + graph.unknownAllocations
                    .filter { it.placementId in tombstones }
                    .mapIndexed { index, allocation ->
                        allocation.copy(
                            placementId = splitTimedId(graph.contribution.contributionId, "recipe", index),
                        )
                    }
                return graph.copy(
                    timedPlacements = timed,
                    unknownAllocations = reAliased,
                )
            }

            // A retired timed share becomes part of this contribution's existing unknown-time
            // recipe. Rebalance the compact total and reset its materialized prefix; keeping old
            // slices would claim stale q/r shares and the exact same minutes twice.
            val oldRecipeTotal = Math.addExact(
                Math.multiplyExact(recipe.quotient ?: 0L, recipe.eligibleCount.toLong()),
                recipe.remainderCount ?: 0L,
            )
            val newRecipeTotal = Math.addExact(oldRecipeTotal, removedTimedMinutes)
            val eligible = recipe.eligibleCount.toLong()
            require(eligible > 0L) { "cannot re-alias funded time into an empty recipe window" }
            return graph.copy(
                timedPlacements = timed,
                unknownAllocations = emptyList(),
                recipe = recipe.copy(
                    quotient = newRecipeTotal / eligible,
                    remainderCount = newRecipeTotal % eligible,
                    materializedMinutes = 0L,
                    materializedThroughDate = null,
                ),
            )
        }
        val removedTimed = graph.timedPlacements.filter { it.placementId in tombstones }
        val removedAllocations = graph.unknownAllocations.filter { it.placementId in tombstones }
        var replacementIndex = 0
        val replacements = removedTimed.map { placement ->
            BackupUnknownAllocation(
                placementId = splitTimedId(
                    graph.contribution.contributionId, "replaced", replacementIndex++,
                ),
                contributionId = graph.contribution.contributionId,
                account = graph.contribution.account,
                appId = graph.contribution.appId,
                revision = graph.contribution.revision,
                date = placement.creditedDate,
                minutes = placement.minutes,
                method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
                zone = placement.zone,
                windowStartAt = graph.contribution.windowStartAt,
                windowEndAt = graph.contribution.windowEndAt,
                pendingDate = false,
                hiddenAtCredit = placement.hiddenAtCredit,
                goalAtCredit = placement.goalAtCredit,
                createdAt = placement.createdAt,
                superseded = false,
                protected = false,
            )
        }
        val reAliasedAllocations = removedAllocations.map { allocation ->
            allocation.copy(
                placementId = splitTimedId(
                    graph.contribution.contributionId, "replaced", replacementIndex++,
                ),
                superseded = false,
            )
        }
        check(removedMinutes == replacements.sumOf { it.minutes } + reAliasedAllocations.sumOf { it.minutes }) {
            "tombstone aliasing lost funded quantity for ${graph.contribution.contributionId}"
        }
        val allocations = datedKept + replacements + reAliasedAllocations
        return graph.copy(
            timedPlacements = timed,
            unknownAllocations = allocations,
        )
    }

    // ------------------------------------------------------------------ precedence

    private val WINNER_COMPARATOR = compareBy<Sourced>(
        { quality(it.graph) },
        { -it.graph.contribution.revision },
        { if (it.imported) 1 else 0 },
        { it.graph.contribution.contributionId },
    )

    /** Placement provenance quality: protected < validated timing < estimate. */
    private fun quality(graph: RecoveryContributionGraph): Int = when {
        graph.hasProtectedPlacement() -> 0
        graph.hasValidatedTiming() -> 1
        else -> 2
    }

    private fun RecoveryContributionGraph.hasProtectedPlacement(): Boolean =
        timedPlacements.any { it.protected } || unknownAllocations.any { it.protected }

    private fun RecoveryContributionGraph.hasValidatedTiming(): Boolean =
        timedPlacements.any {
            it.evidenceQuality == RecoveryEvidenceQuality.LOCAL ||
                it.evidenceQuality == RecoveryEvidenceQuality.CLOUD
        }

    /** Placement facts excluding portable surrogate ids/revision numbers, for ambiguity reporting. */
    private fun placementFingerprint(graph: RecoveryContributionGraph): String = buildString {
        graph.timedPlacements.sortedWith(compareBy({ it.creditedDate }, { it.minutes }, { it.evidenceIdentity }))
            .forEach { share ->
                append("T|").append(share.creditedDate).append('|').append(share.minutes).append('|')
                    .append(share.zone).append('|').append(share.evidenceQuality).append('|')
                    .append(share.protected).append('|').append(share.sessionStartAt).append('|')
                    .append(share.sessionEndAt).append(';')
            }
        graph.unknownAllocations.sortedWith(compareBy({ it.date }, { it.minutes }, { it.method }))
            .forEach { allocation ->
                append("U|").append(allocation.date).append('|').append(allocation.minutes).append('|')
                    .append(allocation.method).append('|').append(allocation.zone).append('|')
                    .append(allocation.protected).append('|').append(allocation.hiddenAtCredit).append('|')
                    .append(allocation.goalAtCredit).append(';')
            }
        graph.recipe?.let { recipe ->
            append("R|").append(recipe.quotient).append('|').append(recipe.remainderCount).append('|')
                .append(recipe.dateStart).append('|').append(recipe.dateEnd).append('|')
                .append(recipe.eligibleCount).append('|').append(recipe.excludedDatesCsv)
        }
    }

    // ------------------------------------------------------------------ helpers

    private data class Sourced(val imported: Boolean, val graph: RecoveryContributionGraph)

    private fun key(graph: RecoveryContributionGraph) = Triple(
        graph.contribution.account,
        graph.contribution.appId,
        graph.contribution.baselineEpoch,
    )

    private fun fragmentId(winner: RecoveryContributionGraph, lo: Long, hi: Long): String =
        PlaytimeRecoveryIdentity.contributionId(
            winner.contribution.account,
            winner.contribution.appId,
            winner.contribution.baselineEpoch,
            lo,
            hi,
        )

    private fun contributionGraphs(recovery: BackupRecovery?): List<RecoveryContributionGraph> {
        val contributions = recovery?.contributions.orEmpty()
        if (contributions.isEmpty()) return emptyList()
        val timed = recovery?.timedPlacements.orEmpty().filter { !it.superseded }
        val allocations = recovery?.unknownAllocations.orEmpty().filter { !it.superseded }
        val timedByContribution = timed.groupBy { it.contributionId }
        val allocationsByContribution = allocations.groupBy { it.contributionId }
        val recipes = recovery?.recipes.orEmpty().associateBy { it.contributionId }
        return contributions.map { contribution ->
            val id = contribution.contributionId
            RecoveryContributionGraph(
                contribution = contribution,
                timedPlacements = timedByContribution[id].orEmpty(),
                unknownAllocations = allocationsByContribution[id].orEmpty(),
                recipe = recipes[id],
            )
        }
    }

    private fun placementTombstones(replacements: List<BackupReplacement>): Set<String> =
        replacements.flatMap { it.replacedPlacementIdsCsv.orEmpty().splitToSequence(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun String.epochMilliOrNull(): Long? =
        runCatching { iso8601ToEpochMilli() }.getOrNull()
}
