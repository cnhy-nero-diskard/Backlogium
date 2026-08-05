package com.example.backlogium.domain

import com.example.backlogium.gamification.Gamification
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One collection member's stored signals as the pure derivation consumes them. Mirrors the
 * `:gamification` stance: no clocks, no I/O, no persistence — callers assemble these from
 * stored rows (games, HLTB cache, achievement counts) and render the returned banner values.
 *
 * [name] is null when the member references a game absent from the library (a dangling
 * membership row kept safe by the soft app-id reference). Such a member is omitted from the
 * rendered summary without failing the derivation or dropping the membership row.
 */
data class CollectionMemberSignals(
    val appId: Long,
    val name: String?,
    /** Lifetime Steam playtime, in minutes — the completion-fraction numerator. */
    val playtimeMinutes: Int,
    /** Cached HowLongToBeat completionist length; null = no HLTB data for this game. */
    val completionistMinutes: Int?,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val achievementsUnlocked: Int?,
    val achievementsTotal: Int?,
    /** User's manual done mark for ordered-queue collections; ignored for every other mode. */
    val manualDone: Boolean = false,
) {
    /** Achievements still locked; no stored achievement data contributes zero, not a failure. */
    val achievementsRemaining: Int
        get() = when {
            achievementsUnlocked == null || achievementsTotal == null -> 0
            else -> (achievementsTotal - achievementsUnlocked).coerceAtLeast(0)
        }

    /**
     * This member's completion fraction, reusing [Gamification.goalProgress] (playtime ÷
     * completionist length, clamped 0–1) — the same definition the Library's progress bars and
     * the engine use, so a collection banner never disagrees with a game's own bar. Null when
     * there is no known completion length.
     */
    val completionFraction: Double?
        get() = completionistMinutes?.takeIf { it > 0 }
            ?.let { Gamification.goalProgress(playtimeMinutes, it).fraction }

    /** Fully complete = the engine's goalProgress reached 1.0 (no HLTB length → not complete). */
    val fullyComplete: Boolean
        get() = completionFraction?.let { it >= 1.0 } ?: false

    /** The HLTB estimate selected by a deadline collection, or null when it is unavailable. */
    fun estimateMinutes(basis: CollectionTimeBasis): Int? = when (basis) {
        CollectionTimeBasis.MAIN_STORY -> mainStoryMinutes
        CollectionTimeBasis.MAIN_EXTRA -> mainExtraMinutes
        CollectionTimeBasis.COMPLETIONIST -> completionistMinutes
        CollectionTimeBasis.ALL_STYLES -> allStylesMinutes
    }
}

/**
 * The mode-specific banner values for one collection, derived purely from stored signals.
 * Every banner is a *rendered value the UI formats* — no formatting lives here.
 */
data class CollectionBanner(
    val mode: CollectionMode,
    /** Members with a present game row — the only ones counted anywhere below. */
    val memberCount: Int,
    /**
     * Aggregate completion progress = the mean of member completion fractions over members
     * with a known completion length; null when no member has HLTB data (design.md decision).
     */
    val completionFraction: Double?,
    /** Total locked achievements across members; 0 when no member has achievement data. */
    val achievementsRemaining: Int,
    /** Total unlocked achievements across members with stored achievement data; null when none. */
    val achievementsUnlocked: Int?,
    /** Total achievements across members with stored achievement data; null when none. */
    val achievementsTotal: Int?,
    /** Days from the injected today to the target date; null outside deadline mode or with no target. */
    val daysRemaining: Long?,
    /** True when a deadline target date is on or before the injected today — "passed", not a negative countdown. */
    val deadlinePassed: Boolean,
    /** Selected HLTB basis used by deadline planning. */
    val timeBasis: CollectionTimeBasis,
    /** Total selected HLTB minutes for members with a known estimate. */
    val plannedMinutes: Int?,
    /** Remaining selected HLTB minutes after subtracting stored playtime. */
    val remainingMinutes: Int?,
    /** Available deadline minutes minus [remainingMinutes], positive when there is buffer. */
    val timeDifferentialMinutes: Int?,
    /** Members with no estimate for the selected basis. */
    val unknownDurationCount: Int,
    /** Ordered-queue: the first member in sequence; null when empty. */
    val nextUp: CollectionMemberSignals?,
    /** Ordered-queue: 1-based position of [nextUp] in the sequence; null when empty. */
    val nextUpPosition: Int?,
    /** Ordered-queue: every member fully complete → no next game to act on. */
    val queueCompleted: Boolean,
    val empty: Boolean,
) {
    /** Member count surface shared by every mode; the basic-list banner is just this. */
    val memberCountLabel: String = "$memberCount ${if (memberCount == 1) "game" else "games"}"
}


/**
 * Pure derivation of collection banner values (tasks 2.3–2.6). No Android dependencies, no
 * clocks beyond the injected [LocalDate.today], no network — callers feed stored rows and
 * render the returned values. Plain-JVM testable, mirroring the `:gamification` stance.
 */
object CollectionSummary {

    /**
     * Order a collection's members by its stored sort selection. Ordered-queue mode keeps the
     * stored sequence order regardless of the sort selection (spec: "Ordered-queue uses manual
     * order"); every non-queue mode ignores the sequence order. Every ordering tie-breaks by
     * app id so it never depends on the order Room happened to return.
     */
    fun order(
        mode: CollectionMode,
        sort: CollectionSort,
        members: List<CollectionMemberSignals>,
    ): List<CollectionMemberSignals> {
        if (mode == CollectionMode.ORDERED_QUEUE) return members
        return when (sort) {
            CollectionSort.NAME ->
                members.sortedWith(
                    compareBy<CollectionMemberSignals> { it.name?.lowercase() ?: "" }
                        .thenBy { it.appId },
                )
            CollectionSort.COMPLETION_FRACTION ->
                members.sortedWith(
                    compareByDescending<CollectionMemberSignals> { it.completionFraction ?: -1.0 }
                        .thenBy { it.appId },
                )
            // The deadline is collection-level, so every member shares the same days-remaining
            // (per-game deadlines deferred); the sort is a no-op beyond a deterministic name
            // tie-break. MANUAL_SEQUENCE on a non-queue mode is likewise ignored, falling back
            // to name — spec: "Non-queue modes ignore sequence order".
            CollectionSort.DAYS_REMAINING,
            CollectionSort.MANUAL_SEQUENCE,
            -> members.sortedWith(
                compareBy<CollectionMemberSignals> { it.name.orEmpty().lowercase() }
                    .thenBy { it.appId },
            )
        }
    }

    /**
     * Derive one collection's banner from its config, members, and the injected current date.
     * Members without a present game row ([CollectionMemberSignals.name] == null) are omitted
     * from the summary — they neither count nor fail the derivation.
     */
    fun derive(
        mode: CollectionMode,
        sort: CollectionSort,
        targetDate: LocalDate?,
        members: List<CollectionMemberSignals>,
        today: LocalDate,
        timeBasis: CollectionTimeBasis = CollectionTimeBasis.COMPLETIONIST,
    ): CollectionBanner {
        val present = members.filter { it.name != null }
        val ordered = order(mode, sort, present)

        val fractions = ordered.mapNotNull { it.completionFraction }
        val completionFraction = if (fractions.isEmpty()) null else fractions.average()
        val achievementCounts = ordered.mapNotNull { member ->
            if (member.achievementsUnlocked != null && member.achievementsTotal != null) {
                member.achievementsUnlocked to member.achievementsTotal
            } else {
                null
            }
        }
        val achievementsUnlocked = achievementCounts.takeIf { it.isNotEmpty() }?.sumOf { it.first }
        val achievementsTotal = achievementCounts.takeIf { it.isNotEmpty() }?.sumOf { it.second }
        val achievementsRemaining = achievementCounts.sumOf { (unlocked, total) ->
            (total - unlocked).coerceAtLeast(0)
        }

        val daysRemaining = if (mode == CollectionMode.DEADLINE_GOAL && targetDate != null) {
            ChronoUnit.DAYS.between(today, targetDate)
        } else {
            null
        }
        val deadlinePassed = daysRemaining != null && daysRemaining <= 0
        val estimatedMembers = ordered.mapNotNull { member ->
            member.estimateMinutes(timeBasis)?.takeIf { it > 0 }?.let { estimate ->
                estimate to member.playtimeMinutes
            }
        }
        val plannedMinutes = estimatedMembers.takeIf { it.isNotEmpty() }?.sumOf { it.first }
        val remainingMinutes = estimatedMembers.takeIf { it.isNotEmpty() }
            ?.sumOf { (estimate, played) -> (estimate - played).coerceAtLeast(0) }
        val availableMinutes = daysRemaining?.takeIf {
            mode == CollectionMode.DEADLINE_GOAL && targetDate != null
        }?.let { it * MINUTES_PER_DAY }
        val unknownDurationCount = ordered.count { member ->
            member.estimateMinutes(timeBasis)?.takeIf { it > 0 } == null
        }
        val timeDifferentialMinutes = if (
            availableMinutes != null && remainingMinutes != null && unknownDurationCount == 0
        ) {
            availableMinutes.toInt() - remainingMinutes
        } else {
            null
        }
        val nextUpIndex = if (mode == CollectionMode.ORDERED_QUEUE) {
            ordered.indexOfFirst { !it.manualDone && !it.fullyComplete }
        } else -1
        val nextUp = nextUpIndex.takeIf { it >= 0 }?.let { ordered[it] }
        val queueCompleted = mode == CollectionMode.ORDERED_QUEUE &&
            ordered.isNotEmpty() && ordered.all { it.manualDone || it.fullyComplete }

        return CollectionBanner(
            mode = mode,
            memberCount = ordered.size,
            completionFraction = completionFraction,
            achievementsRemaining = achievementsRemaining,
            achievementsUnlocked = achievementsUnlocked,
            achievementsTotal = achievementsTotal,
            daysRemaining = daysRemaining,
            deadlinePassed = deadlinePassed,
            timeBasis = timeBasis,
            plannedMinutes = plannedMinutes,
            remainingMinutes = remainingMinutes,
            timeDifferentialMinutes = timeDifferentialMinutes,
            unknownDurationCount = unknownDurationCount,
            nextUp = nextUp,
            nextUpPosition = nextUp?.let { nextUpIndex + 1 },
            queueCompleted = queueCompleted,
            empty = ordered.isEmpty(),
        )
    }

    private const val MINUTES_PER_DAY = 24L * 60L
}
