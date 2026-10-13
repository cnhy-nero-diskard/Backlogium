package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.DailyProgress

/**
 * Operation-owned dated-credit deltas (recover-missed-playtime, design.md §7, tasks 5.3/5.4).
 *
 * A correction moves the *operation's own* shares — old revision placements out, new revision
 * placements in — as signed per-date deltas. It never recomputes a whole date from the session
 * ledger plus current visibility: the stored row's unrelated legacy/manual remainder is preserved
 * untouched, and a share hidden at credit time contributes nothing to either side (its frozen
 * eligibility, never today's settings, decides that). Pending date-unknown and superseded rows are
 * stripped before the delta is computed; funded timed shares are carried by their frozen credited
 * date as first-class shares, so a late-evidence replacement that moves minutes out of a session
 * moves exactly those minutes off the old date.
 */

/**
 * A signed change to one date's stored raw totals. [minutesDelta]/[goalDelta] are the difference
 * between what the day should hold and what it holds now, so applying it preserves any unrelated
 * remainder the row carries — the recovery ledger owns its own shares, not the whole row.
 */
data class DailyCreditDelta(
    val date: String,
    val minutesDelta: Int,
    val goalMinutesDelta: Int,
) {
    val isNoOp: Boolean get() = minutesDelta == 0 && goalMinutesDelta == 0
}

/**
 * One dated share of an operation, projected from its frozen storage facts. A share hidden at
 * credit time contributes nothing (it was never dated credit); a dated share counts toward the goal
 * exactly when its frozen Focus flag says so.
 */
data class PlacementShare(
    val date: String,
    val minutes: Long,
    val goalAtCredit: Boolean,
    val hiddenAtCredit: Boolean,
) {
    /** The minutes that actually reach dated daily credit under the frozen eligibility. */
    val creditedMinutes: Long get() = if (hiddenAtCredit) 0L else minutes
    val creditedGoalMinutes: Long get() = if (hiddenAtCredit || !goalAtCredit) 0L else minutes
}

/** Active, dated, positive funded timed shares as [PlacementShare]s (superseded/pending stripped). */
fun datedPlacementShares(placements: Collection<TimedContributionPlacement>): List<PlacementShare> =
    placements.asSequence()
        .filter { !it.superseded && it.creditedDate.isNotEmpty() && it.minutes > 0L }
        .map {
            PlacementShare(
                date = it.creditedDate,
                minutes = it.minutes,
                goalAtCredit = it.goalAtCredit,
                hiddenAtCredit = it.hiddenAtCredit,
            )
        }
        .toList()

/** Active dated unknown-time allocations as [PlacementShare]s (superseded and pending stripped). */
fun datedAllocationShares(allocations: Collection<UnknownTimeAllocation>): List<PlacementShare> =
    allocations.asSequence()
        .filter { !it.superseded && !it.pendingDate && it.date != null && it.minutes > 0L }
        .map {
            PlacementShare(
                date = it.date!!,
                minutes = it.minutes,
                goalAtCredit = it.goalAtCredit,
                hiddenAtCredit = it.hiddenAtCredit,
            )
        }
        .toList()

/**
 * The signed, per-date deltas that move the placement graph from [before] to [after] for one
 * operation. Hidden shares contribute 0 to both sides, so a move that merely re-positions
 * hidden-at-credit minutes changes no dated row. All arithmetic is checked wide; an intermediate
 * delta outside the daily columns' `Int` range is rejected, never wrapped.
 */
fun placementShareDeltas(
    before: List<PlacementShare>,
    after: List<PlacementShare>,
): List<DailyCreditDelta> {
    fun totals(shares: List<PlacementShare>): Map<String, Pair<Long, Long>> =
        shares.groupBy { it.date }
            .mapValues { (_, dayShares) ->
                dayShares.sumOf { it.creditedMinutes } to dayShares.sumOf { it.creditedGoalMinutes }
            }

    val beforeTotals = totals(before)
    val afterTotals = totals(after)
    val dates = (beforeTotals.keys + afterTotals.keys).sorted()

    return dates.mapNotNull { date ->
        val beforeMinutes = beforeTotals[date]?.first ?: 0L
        val beforeGoal = beforeTotals[date]?.second ?: 0L
        val afterMinutes = afterTotals[date]?.first ?: 0L
        val afterGoal = afterTotals[date]?.second ?: 0L
        val minutesDelta = checkedIntDelta(afterMinutes, beforeMinutes, "minutes", date)
        val goalDelta = checkedIntDelta(afterGoal, beforeGoal, "goal minutes", date)
        if (minutesDelta == 0 && goalDelta == 0) {
            null
        } else {
            DailyCreditDelta(date, minutesDelta, goalDelta)
        }
    }
}

private fun checkedIntDelta(after: Long, before: Long, what: String, date: String): Int {
    val delta = after - before
    require(delta in Int.MIN_VALUE..Int.MAX_VALUE) {
        "daily $what delta for $date overflows Int: $after - $before"
    }
    return delta.toInt()
}

/**
 * A corrective derived pass sees raw DailyProgress after the operation's earned deltas have been
 * committed. Remove only those exact fresh dated shares from the in-memory quest inputs; never
 * mutate the raw row or erase an unrelated legacy/manual remainder on the same date. The subsequent
 * earned pass recomputes against the full raw rows and can emit the ordinary event exactly once.
 */
fun recoveryCorrectiveDailyProgress(
    stored: List<DailyProgress>,
    exclusions: RecoveryEarnedInputs,
): List<DailyProgress> {
    val byDate = exclusions.datedCredits.groupBy { it.date }
        .mapValues { (date, rows) ->
            rows.fold(0L to 0L) { (minutes, goals), row ->
                Math.addExact(minutes, row.minutes.toLong()) to
                    Math.addExact(goals, row.goalMinutes.toLong())
            }.also { (minutes, goals) ->
                require(goals <= minutes) { "earned goal exclusion exceeds its dated minutes on $date" }
            }
        }
    if (byDate.isEmpty()) return stored
    val storedByDate = stored.associateBy { it.date }
    byDate.forEach { (date, excluded) ->
        val row = storedByDate[date] ?: error("fresh earned daily credit has no stored date row: $date")
        require(excluded.first <= row.minutesPlayed.toLong() && excluded.second <= row.goalMinutesPlayed.toLong()) {
            "fresh earned daily credit exceeds stored raw totals on $date"
        }
    }
    return stored.map { row ->
        val excluded = byDate[row.date] ?: return@map row
        row.copy(
            minutesPlayed = Math.toIntExact(row.minutesPlayed.toLong() - excluded.first),
            goalMinutesPlayed = Math.toIntExact(row.goalMinutesPlayed.toLong() - excluded.second),
        )
    }
}

// ------------------------------------------------------------------ corrective compute exclusions

/**
 * The exact quantity the corrective stage's XP baseline must subtract: the fresh earned
 * contributions' authorized minutes, keyed by identity — never a whole game. For the
 * `GamificationUpdater` exclusion parameter, `current` is `creditedMinutesByGame()` after the raw
 * commit; the result keeps every unrelated minute (historical, backfill, manual, past recovery) and
 * removes only the named fresh contributions.
 *
 * Example: a game with 500 previously credited minutes + a 35-minute past recovery commit + a
 * 10-minute fresh current share reports `current[app]=545`; excluding the 10-minute fresh
 * contribution yields 535 — the existing 500 and the past 35 are untouched, and the game is never
 * zeroed.
 */
fun recoveryCorrectiveCreditedByGame(
    current: Map<Long, Long>,
    exclusions: RecoveryEarnedInputs,
): Map<Long, Long> {
    val perGame = exclusions.contributionMinutesByGame
    return current.mapValues { (appId, minutes) ->
        val excluded = perGame[appId] ?: return@mapValues minutes
        require(excluded <= minutes) {
            "corrective exclusion of $excluded minute(s) exceeds credited total $minutes for app $appId " +
                "— inconsistent quantity graph"
        }
        minutes - excluded
    }
}

/**
 * Every unlocked achievement entry whose (app id, api name) is NOT freshly earned — the corrective
 * stage's achievement list. Matching is by the unambiguous pair, so an api name shared by two games
 * excludes only the claimed app's achievement.
 */
fun recoveryCorrectiveAchievements(
    unlockedKeys: Collection<RecoveryAchievementKey>,
    exclusions: RecoveryEarnedInputs,
): List<RecoveryAchievementKey> =
    unlockedKeys.filterNot { it in exclusions.achievementKeys }
