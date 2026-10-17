package com.example.backlogium.domain

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import java.time.LocalDate
import javax.inject.Inject
import timber.log.Timber

/**
 * Journaled, resumable materialization of a compact unknown-time recipe into per-date allocation
 * rows (recover-missed-playtime, design.md §7, task 5.4).
 *
 * A long recovery window commits its authoritative `R = quotient * N + remainder` recipe with the
 * baseline; that recipe *is* the raw daily credit. Materialization only turns bounded slices of it
 * into the per-date rows History, Analytics and daily quests read, so a ten-year gap never needs a
 * ten-year write in one transaction. Progress is durable read-state
 * ([UnknownTimeAllocationRecipe.materializedThroughDate]) rather than an in-memory cursor, so a
 * process death mid-window resumes at the next unmaterialized date.
 *
 * The slice insert and the raw daily credit for its dates share one Room transaction: the recipe
 * residual is re-read from the allocation rows after every slice, so a retry can never strand a
 * date, expose a partially credited slice, or count a materialized share twice. Readers combine the
 * recipe's not-yet-materialized dates with the materialized rows exactly once because materialization
 * is prefix-ordered — every date at or before [materializedThroughDate] is a row, every later date is
 * recipe.
 */
class UnknownTimeMaterializer @Inject constructor(
    private val database: BacklogiumDatabase,
    private val recoveryDao: PlaytimeRecoveryDao,
    private val dailyCredit: RecoveryDailyCreditCoordinator,
) {

    /** Drain every active-account recipe in bounded, restartable slices before derived evaluation. */
    suspend fun materializeAll(
        account: String,
        now: Long,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): Int {
        require(pageSize > 0)
        if (recoveryDao.activeAccount() != account) return 0
        var slices = 0
        val ids = recoveryDao.contributionsForAccount(account).map { it.contributionId }
        for (id in ids) {
            while (recoveryDao.activeAccount() == account) {
                val current = recoveryDao.recipe(id) ?: break
                if (current.account != account || current.pending) break
                val slice = materialize(id, now, pageSize) ?: break
                slices++
                if (slice.complete) break
            }
            if (recoveryDao.activeAccount() != account) break
        }
        return slices
    }

    /** What one materialization step wrote, and whether the whole recipe is now materialized. */
    data class Slice(
        val dates: List<String>,
        val minutes: Int,
        val complete: Boolean,
    ) {
        val isEmpty: Boolean get() = dates.isEmpty()
    }

    /**
     * Materialize the next bounded slice of [contributionId]'s recipe, applying raw daily credit for
     * exactly the newly materialized dates in the same transaction.
     *
     * Idempotent: re-running after a successful slice returns an empty slice and changes nothing,
     * and a window whose page is entirely excluded/non-positive simply advances its durable cursor
     * without writing a row.
     */
    suspend fun materialize(
        contributionId: String,
        now: Long,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): Slice? {
        require(pageSize >= 1) { "pageSize must be positive" }
        val recipeRow = recoveryDao.recipe(contributionId) ?: return null
        if (recipeRow.pending) return null
        if (recoveryDao.activeAccount() != recipeRow.account || !recoveryDao.validateGraph(contributionId)) {
            Timber.w("Skipping materialization for invalid recovery graph %s", contributionId)
            return null
        }
        val recipe = runCatching { recipeRow.toResidualRecipe() }.getOrElse { error ->
            Timber.w(error, "Skipping malformed recovery recipe %s", contributionId)
            return null
        }

        val from = recipeRow.materializedThroughDate
            ?.let { LocalDate.parse(it).plusDays(1) }
            ?: recipe.firstDate
        if (from.isAfter(recipe.lastDate)) {
            return Slice(emptyList(), 0, complete = true)
        }
        val to = minOf(recipe.lastDate, from.plusDays(pageSize.toLong() - 1))
        val allocations = PlaytimeAllocationRecipes.between(recipe, from, to)
            .map { allocation(recipeRow, it) }

        if (allocations.isEmpty()) {
            // Every date in this page is either excluded or rounded to a zero share. No rows are
            // written; the durable cursor still advances past the page so the next call makes
            // progress. The fresh recipe is re-read inside the transaction and only ever moves the
            // cursor *forward*, never clobbering the storage layer's authoritative
            // [UnknownTimeAllocationRecipe.materializedMinutes] read-back count.
            database.withTransaction {
                val fresh = recoveryDao.recipe(contributionId) ?: return@withTransaction
                if (recoveryDao.activeAccount() != fresh.account) return@withTransaction
                val currentCursor = fresh.materializedThroughDate?.let { LocalDate.parse(it) }
                if (currentCursor == null || currentCursor.isBefore(to)) {
                    recoveryDao.upsertRecipe(
                        fresh.copy(updatedAt = now, materializedThroughDate = to.toString()),
                    )
                }
            }
            return Slice(emptyList(), 0, complete = to == recipe.lastDate)
        }

        var newlyMaterialized: List<UnknownTimeAllocation> = emptyList()
        database.withTransaction {
            if (recoveryDao.activeAccount() != recipeRow.account) return@withTransaction
            // The recipe is the only quantity authority for the whole residual; these rows are its
            // slices. The raw daily credit for exactly these dates is applied in the same
            // transaction the rows are created in, so an interruption can never expose a
            // partially-credited slice, and the durable cursor makes a re-run a no-op rather than a
            // second credit.
            val existingIds = recoveryDao.activeAllocations(contributionId)
                .asSequence().map { it.placementId }.toSet()
            newlyMaterialized = allocations.filterNot { it.placementId in existingIds }
            recoveryDao.materializeRecipeSlice(allocations)
            dailyCredit.apply(
                newlyMaterialized.map { allocation ->
                    DailyCreditDelta(
                        date = allocation.date!!,
                        minutesDelta = allocation.minutes.toInt(),
                        goalMinutesDelta = if (allocation.goalAtCredit) allocation.minutes.toInt() else 0,
                    )
                },
            )
        }
        val minutesTotal = newlyMaterialized.fold(0L) { total, allocation ->
            Math.addExact(total, allocation.minutes)
        }
        check(minutesTotal <= Int.MAX_VALUE) {
            "materialization slice minutes $minutesTotal overflow Int"
        }
        return Slice(
            dates = newlyMaterialized.mapNotNull { it.date },
            minutes = minutesTotal.toInt(),
            complete = to == recipe.lastDate
                || PlaytimeAllocationRecipes.between(recipe, to.plusDays(1), recipe.lastDate).isEmpty(),
        )
    }

    /**
     * The recipe-derived dates that are *not yet* materialized, for a bounded slice. A reader sums
     * these with the materialized rows: a date at or before the durable cursor is a row, a later
     * date is recipe, so no share is counted twice and no date is lost.
     *
     * @deprecated storage agents' `PlaytimeReadProjector.recipeActivities`/`datedCreditedTotals`
     *   already own this coherent "materialized rows + recipe tail exactly once" read; prefer those.
     */
    @Deprecated("use PlaytimeReadProjector.recipeActivities / datedCreditedTotals")
    suspend fun unmaterializedCredit(
        contributionId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<DatedAllocation> {
        val recipeRow = recoveryDao.recipe(contributionId) ?: return emptyList()
        if (recipeRow.pending) return emptyList()
        if (!recoveryDao.validateGraph(contributionId)) return emptyList()
        val recipe = runCatching { recipeRow.toResidualRecipe() }.getOrElse { error ->
            Timber.w(error, "Refusing to expand malformed recovery recipe %s", contributionId)
            return emptyList()
        }
        val start = recipeRow.materializedThroughDate
            ?.let { LocalDate.parse(it).plusDays(1) }
            ?.let { maxOf(it, from) }
            ?: maxOf(from, recipe.firstDate)
        if (start.isAfter(to)) return emptyList()
        return PlaytimeAllocationRecipes.between(recipe, start, to)
    }

    private fun allocation(
        recipeRow: UnknownTimeAllocationRecipe,
        allocation: DatedAllocation,
    ): UnknownTimeAllocation = UnknownTimeAllocation(
        placementId = PlaytimeRecoveryIdentity.allocationPlacementId(
            contributionId = recipeRow.contributionId,
            revision = recipeRow.revision,
            date = allocation.date.toString(),
        ),
        contributionId = recipeRow.contributionId,
        account = recipeRow.account,
        appId = recipeRow.appId,
        revision = recipeRow.revision,
        date = allocation.date.toString(),
        minutes = allocation.minutes.toLong(),
        method = recipeRow.method,
        zone = recipeRow.zone,
        windowStartAt = recipeRow.windowStartAt,
        windowEndAt = recipeRow.windowEndAt,
        pendingDate = false,
        hiddenAtCredit = recipeRow.hiddenAtCredit,
        goalAtCredit = recipeRow.goalAtCredit,
        createdAt = 0L,
    )

    companion object {
        /** A year of dates per slice: bounded work, and a ten-year window needs ten steps. */
        const val DEFAULT_PAGE_SIZE = 366
    }
}

/**
 * Parse the compact recipe's stored method into the planner's allocation method. A recipe is always
 * estimated, so an unexpected marker falls back to a multi-day estimate rather than failing the
 * read.
 */
internal fun recipeAllocationMethod(method: String): PlaytimeAllocationMethod =
    when (method) {
        RecoveryAllocationMethods.SINGLE_DAY -> PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE
        RecoveryAllocationMethods.MULTI_DAY_ESTIMATE -> PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE
        else -> PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE
    }

/** Expand a stored compact recipe into the pure exact-allocation recipe the pager can expand. */
internal fun UnknownTimeAllocationRecipe.toResidualRecipe(): ResidualDateRecipe {
    val first = requireNotNull(dateStart) { "recipe $contributionId has no start date" }
    val last = requireNotNull(dateEnd) { "recipe $contributionId has no end date" }
    val firstDate = LocalDate.parse(first)
    val lastDate = LocalDate.parse(last)
    val excluded = excludedDatesCsv.orEmpty().split(',')
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .map { token ->
            val separator = token.indexOf("..")
            if (separator < 0) {
                val date = LocalDate.parse(token)
                date..date
            } else {
                val first = LocalDate.parse(token.substring(0, separator))
                val last = LocalDate.parse(token.substring(separator + 2))
                require(!last.isBefore(first)) { "inverted recipe exclusion range '$token'" }
                first..last
            }
        }
    val quotient = quotient ?: 0L
    val remainder = remainderCount ?: 0L
    val total = Math.addExact(
        Math.multiplyExact(checkedRecipeLong(quotient), eligibleCount.toLong()),
        checkedRecipeLong(remainder),
    )
    return ResidualDateRecipe(
        method = recipeAllocationMethod(method),
        firstDate = firstDate,
        lastDate = lastDate,
        eligibleDateCount = eligibleCount,
        baseMinutes = checkedRecipeInt(quotient, "quotient"),
        remainderCount = checkedRecipeInt(remainder, "remainder"),
        totalMinutes = checkedRecipeInt(total, "recipe total"),
        excludedRanges = PlaytimeAllocationRecipes.normalizeRanges(excluded),
    )
}

/** Wider arithmetic before the pure model's Int slices: a wrapping value is rejected, never used. */
private fun checkedRecipeLong(value: Long): Long = value.also {
    require(it >= 0L) { "negative recipe quantity $it" }
}

private fun checkedRecipeInt(value: Long, what: String): Int {
    require(value in Int.MIN_VALUE..Int.MAX_VALUE) {
        "recipe $what $value overflows Int"
    }
    return value.toInt()
}
