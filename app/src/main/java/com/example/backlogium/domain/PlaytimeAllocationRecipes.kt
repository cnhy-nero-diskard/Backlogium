package com.example.backlogium.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A compact, exact description of a residual allocation that may span a very long window.
 *
 * The plan commits this recipe with the baseline rather than thousands of rows: `baseMinutes` for
 * every eligible date and one extra minute for the earliest [remainderCount] eligible dates.
 * Construction is O(evidence spans): the window count is calendar arithmetic and [excludedRanges]
 * come only from dates the usable timing evidence fully covers. Expansion is bounded and skips
 * zero shares, so a one-minute gap over ten years produces one row rather than a year of zeros, and
 * no arbitrary cap can drop a minute.
 */
data class ResidualDateRecipe(
    val method: PlaytimeAllocationMethod,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val eligibleDateCount: Int,
    val baseMinutes: Int,
    val remainderCount: Int,
    val totalMinutes: Int,
    val excludedRanges: List<ClosedRange<LocalDate>> = emptyList(),
) {
    init {
        require(!lastDate.isBefore(firstDate)) { "firstDate must not follow lastDate" }
        require(eligibleDateCount >= 1) { "a residual recipe needs at least one eligible date" }
        require(baseMinutes >= 0) { "baseMinutes must not be negative" }
        require(remainderCount in 0 until eligibleDateCount) {
            "remainderCount must be smaller than the eligible date count"
        }
        // Wider checked conservation: product can overflow Int on very long or very heavy windows.
        require(
            baseMinutes.toLong() * eligibleDateCount + remainderCount == totalMinutes.toLong(),
        ) { "recipe does not conserve its own total" }
        require(excludedRanges.all { !it.start.isBefore(firstDate) && !it.endInclusive.isAfter(lastDate) }) {
            "excluded ranges must lie inside the window"
        }
        require(PlaytimeAllocationRecipes.normalizeRanges(excludedRanges) == excludedRanges) {
            "excluded ranges must be sorted, merged and non-overlapping"
        }
        require(windowDateCount - excludedDateCount == eligibleDateCount) {
            "eligible count must equal the window minus the excluded dates"
        }
    }

    val windowDateCount: Int get() = PlaytimeAllocationRecipes.calendarDays(firstDate, lastDate)

    val excludedDateCount: Int
        get() = excludedRanges.sumOf { PlaytimeAllocationRecipes.calendarDays(it.start, it.endInclusive) }
}

object PlaytimeAllocationRecipes {

    /** Inclusive calendar-day count from [from] to [to], 0 when [to] precedes [from]. */
    fun calendarDays(from: LocalDate, to: LocalDate): Int {
        if (to.isBefore(from)) return 0
        val days = ChronoUnit.DAYS.between(from, to)
        require(days < Int.MAX_VALUE) { "date window exceeds a representable day count" }
        return (days + 1L).toInt()
    }

    /** Sorted, merged, non-overlapping normalization of exclusion ranges. */
    fun normalizeRanges(ranges: List<ClosedRange<LocalDate>>): List<ClosedRange<LocalDate>> {
        val sorted = ranges.sortedBy { it.start }
        val merged = mutableListOf<ClosedRange<LocalDate>>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.start <= last.endInclusive.plusDays(1)) {
                val end = maxOf(last.endInclusive, range.endInclusive)
                merged[merged.lastIndex] = last.start..end
            } else {
                merged += range
            }
        }
        return merged
    }

    /** Number of excluded dates within the inclusive [from]..[to] slice. */
    fun excludedCount(ranges: List<ClosedRange<LocalDate>>): Int =
        ranges.sumOf { range -> calendarDays(range.start, range.endInclusive) }

    /** Materialize every positive share of [recipe]. Reserved for bounded windows and tests. */
    fun positiveAllocations(recipe: ResidualDateRecipe): List<DatedAllocation> =
        between(recipe, recipe.firstDate, recipe.lastDate)

    /**
     * Positive shares for the inclusive date range [from]..[to], clipped to the recipe. Work is
     * proportional to the requested page plus the (few) excluded ranges, not to the whole window.
     */
    fun between(recipe: ResidualDateRecipe, from: LocalDate, to: LocalDate): List<DatedAllocation> {
        val start = maxOf(from, recipe.firstDate)
        val end = minOf(to, recipe.lastDate)
        if (end.isBefore(start)) return emptyList()

        val excludedBefore = excludedCount(recipe.excludedRanges, recipe.firstDate, start.minusDays(1))
        val eligibleBefore = calendarDays(recipe.firstDate, start.minusDays(1)) - excludedBefore
        var eligibleIndex = eligibleBefore

        val allocations = ArrayList<DatedAllocation>()
        var date = start
        while (!date.isAfter(end)) {
            if (!isExcluded(recipe.excludedRanges, date)) {
                val minutes = recipe.baseMinutes + if (eligibleIndex < recipe.remainderCount) 1 else 0
                if (minutes > 0) allocations += DatedAllocation(date, minutes, recipe.method)
                eligibleIndex++
            }
            date = date.plusDays(1)
        }
        return allocations
    }

    /** Excluded-date count within the inclusive [from]..[to] slice of the recipe. */
    fun excludedCount(
        ranges: List<ClosedRange<LocalDate>>,
        from: LocalDate,
        to: LocalDate,
    ): Int = ranges.sumOf { range ->
        val s = maxOf(range.start, from)
        val e = minOf(range.endInclusive, to)
        calendarDays(s, e)
    }

    fun isExcluded(ranges: List<ClosedRange<LocalDate>>, date: LocalDate): Boolean =
        ranges.any { date in it }

    /** A fixed-size calendar page of a long recipe, beginning at [recipe]'s first date. */
    fun page(recipe: ResidualDateRecipe, pageIndex: Int, pageSize: Int): List<DatedAllocation> {
        require(pageIndex >= 0) { "pageIndex must not be negative" }
        require(pageSize >= 1) { "pageSize must be positive" }
        val from = recipe.firstDate.plusDays(pageIndex.toLong() * pageSize)
        if (from.isAfter(recipe.lastDate)) return emptyList()
        val to = minOf(recipe.lastDate, from.plusDays((pageSize - 1).toLong()))
        return between(recipe, from, to)
    }

    /** Number of [page]-sized pages needed to cover the recipe. */
    fun pageCount(recipe: ResidualDateRecipe, pageSize: Int): Int {
        require(pageSize >= 1) { "pageSize must be positive" }
        val days = calendarDays(recipe.firstDate, recipe.lastDate)
        val pages = (days.toLong() + pageSize - 1) / pageSize
        require(pages <= Int.MAX_VALUE) { "recipe needs too many pages to represent" }
        return pages.toInt()
    }
}