package com.example.backlogium.domain

import com.example.backlogium.gamification.Gamification
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One collection member's stored signals as the pure derivation consumes them. Mirrors the
 * gamification engine's no-I/O stance: callers assemble these from local rows and render the
 * returned values.
 */
data class CollectionMemberSignals(
    val appId: Long,
    val name: String?,
    val playtimeMinutes: Int,
    val completionistMinutes: Int?,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val achievementsUnlocked: Int?,
    val achievementsTotal: Int?,
    val manualDone: Boolean = false,
) {
    val achievementsRemaining: Int
        get() = when {
            achievementsUnlocked == null || achievementsTotal == null -> 0
            else -> (achievementsTotal - achievementsUnlocked).coerceAtLeast(0)
        }

    val completionFraction: Double?
        get() = completionistMinutes?.takeIf { it > 0 }
            ?.let { Gamification.goalProgress(playtimeMinutes, it).fraction }

    val fullyComplete: Boolean
        get() = completionFraction?.let { it >= 1.0 } ?: false

    fun estimateMinutes(basis: CollectionTimeBasis): Int? = when (basis) {
        CollectionTimeBasis.MAIN_STORY -> mainStoryMinutes
        CollectionTimeBasis.MAIN_EXTRA -> mainExtraMinutes
        CollectionTimeBasis.COMPLETIONIST -> completionistMinutes
        CollectionTimeBasis.ALL_STYLES -> allStylesMinutes
    }
}

enum class CollectionPacingState {
    ON_TRACK,
    AT_RISK,
    LEARNING,
    INCOMPLETE_DATA,
}

/** Purely derived collection values consumed by both Home and the collection overview. */
data class CollectionBanner(
    val mode: CollectionMode,
    val memberCount: Int,
    val completionFraction: Double?,
    val achievementsRemaining: Int,
    val achievementsUnlocked: Int?,
    val achievementsTotal: Int?,
    val daysRemaining: Long?,
    val deadlinePassed: Boolean,
    val timeBasis: CollectionTimeBasis,
    val plannedMinutes: Int?,
    val remainingMinutes: Int?,
    /** Compatibility-shaped integer view; new presentation should use [capacityMarginMinutes]. */
    val timeDifferentialMinutes: Int?,
    val unknownDurationCount: Int,
    val nextUp: CollectionMemberSignals?,
    val nextUpPosition: Int?,
    val queueCompleted: Boolean,
    val empty: Boolean,
    val paceConfidence: PersonalPaceConfidence?,
    val pacingState: CollectionPacingState?,
    val projectedCapacityMinutes: Double?,
    val projectedActiveDays: Double?,
    val recentTrackedPaceMinutes: Double?,
    val requiredMinutesPerActiveDay: Double?,
    val capacityMarginMinutes: Double?,
    val deadlineInterventionEligible: Boolean,
    val estimatedFitDate: LocalDate?,
    val completionHorizonDate: LocalDate?,
    val nextGameHorizonDate: LocalDate?,
    val queueHorizonDate: LocalDate?,
    val pacingForecast: PersonalPaceForecast?,
) {
    val memberCountLabel: String = "$memberCount ${if (memberCount == 1) "game" else "games"}"
}

/** Pure collection banner derivation. No Android dependencies, clocks, network, or persistence. */
object CollectionSummary {

    fun order(
        mode: CollectionMode,
        sort: CollectionSort,
        members: List<CollectionMemberSignals>,
    ): List<CollectionMemberSignals> {
        if (mode == CollectionMode.ORDERED_QUEUE) return members
        return when (sort) {
            CollectionSort.NAME -> members.sortedWith(
                compareBy<CollectionMemberSignals> { it.name?.lowercase() ?: "" }
                    .thenBy { it.appId },
            )
            CollectionSort.COMPLETION_FRACTION -> members.sortedWith(
                compareByDescending<CollectionMemberSignals> { it.completionFraction ?: -1.0 }
                    .thenBy { it.appId },
            )
            CollectionSort.DAYS_REMAINING,
            CollectionSort.MANUAL_SEQUENCE,
            -> members.sortedWith(
                compareBy<CollectionMemberSignals> { it.name.orEmpty().lowercase() }
                    .thenBy { it.appId },
            )
        }
    }

    fun derive(
        mode: CollectionMode,
        sort: CollectionSort,
        targetDate: LocalDate?,
        members: List<CollectionMemberSignals>,
        today: LocalDate,
        timeBasis: CollectionTimeBasis = CollectionTimeBasis.COMPLETIONIST,
        personalPace: PersonalPaceProfile? = null,
    ): CollectionBanner {
        val ordered = order(mode, sort, members.filter { it.name != null })
        if (ordered.isEmpty()) {
            return CollectionBanner(
                mode = mode,
                memberCount = 0,
                completionFraction = null,
                achievementsRemaining = 0,
                achievementsUnlocked = null,
                achievementsTotal = null,
                daysRemaining = null,
                deadlinePassed = false,
                timeBasis = timeBasis,
                plannedMinutes = null,
                remainingMinutes = null,
                timeDifferentialMinutes = null,
                unknownDurationCount = 0,
                nextUp = null,
                nextUpPosition = null,
                queueCompleted = false,
                empty = true,
                paceConfidence = null,
                pacingState = null,
                projectedCapacityMinutes = null,
                projectedActiveDays = null,
                recentTrackedPaceMinutes = null,
                requiredMinutesPerActiveDay = null,
                capacityMarginMinutes = null,
                deadlineInterventionEligible = false,
                estimatedFitDate = null,
                completionHorizonDate = null,
                nextGameHorizonDate = null,
                queueHorizonDate = null,
                pacingForecast = null,
            )
        }

        val fractions = ordered.mapNotNull { it.completionFraction }
        val completionFraction = fractions.takeIf { it.isNotEmpty() }?.average()
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
        val nextUpIndex = if (mode == CollectionMode.ORDERED_QUEUE) {
            ordered.indexOfFirst { !it.manualDone && !it.fullyComplete }
        } else {
            -1
        }
        val nextUp = nextUpIndex.takeIf { it >= 0 }?.let(ordered::get)
        val queueCompleted = mode == CollectionMode.ORDERED_QUEUE &&
            ordered.isNotEmpty() && ordered.all { it.manualDone || it.fullyComplete }

        val pacingMembers = when (mode) {
            CollectionMode.BASIC -> emptyList()
            CollectionMode.DEADLINE_GOAL -> ordered
            CollectionMode.COMPLETION_GOAL -> ordered.filterNot { it.fullyComplete }
            CollectionMode.ORDERED_QUEUE -> ordered.filterNot { it.manualDone || it.fullyComplete }
        }
        val knownEstimates = pacingMembers.mapNotNull { member ->
            member.estimateMinutes(timeBasisFor(mode, timeBasis))
                ?.takeIf { it > 0 }
                ?.let { it to member.playtimeMinutes }
        }
        val plannedMinutes = if (mode == CollectionMode.BASIC) {
            null
        } else {
            knownEstimates.takeIf { it.isNotEmpty() }?.sumOf { it.first }
        }
        val remainingMinutes = if (mode == CollectionMode.BASIC) {
            null
        } else {
            knownEstimates.takeIf { it.isNotEmpty() }
                ?.sumOf { (estimate, played) -> (estimate - played).coerceAtLeast(0) }
        }
        val unknownDurationCount = if (mode == CollectionMode.BASIC) {
            0
        } else {
            pacingMembers.count {
                it.estimateMinutes(timeBasisFor(mode, timeBasis))?.takeIf { value -> value > 0 } == null
            }
        }

        var pacingState: CollectionPacingState? = null
        var projectedCapacityMinutes: Double? = null
        var projectedActiveDays: Double? = null
        val recentTrackedPaceMinutes = personalPace
            ?.takeIf { mode != CollectionMode.BASIC && it.activeDates > 0 }
            ?.typicalActiveDayMinutes
        var requiredMinutesPerActiveDay: Double? = null
        var capacityMarginMinutes: Double? = null
        var estimatedFitDate: LocalDate? = null
        var completionHorizonDate: LocalDate? = null
        var nextGameHorizonDate: LocalDate? = null
        var queueHorizonDate: LocalDate? = null
        var pacingForecast: PersonalPaceForecast? = null

        when (mode) {
            CollectionMode.BASIC -> Unit
            CollectionMode.DEADLINE_GOAL -> {
                val target = targetDate
                if (target != null) {
                    when {
                        unknownDurationCount > 0 -> pacingState = CollectionPacingState.INCOMPLETE_DATA
                        personalPace == null || personalPace.confidence != PersonalPaceConfidence.RELIABLE -> {
                            pacingState = CollectionPacingState.LEARNING
                        }
                        else -> {
                            val deadline = target ?: error("A deadline pacing state requires a target")
                            val pace = personalPace ?: error("Reliable pacing requires a profile")
                            val knownRemainingMinutes = remainingMinutes ?: 0
                            pacingForecast = if (deadline.isAfter(today)) {
                                pace.forecast(today.plusDays(1), deadline, knownRemainingMinutes)
                            } else {
                                null
                            }
                            projectedCapacityMinutes = pacingForecast?.expectedGamingMinutes
                            projectedActiveDays = pacingForecast?.expectedActiveDays
                            requiredMinutesPerActiveDay = pacingForecast
                                ?.requiredMinutesPerProjectedActiveDay
                            capacityMarginMinutes = pacingForecast?.let {
                                it.expectedGamingMinutes - knownRemainingMinutes
                            } ?: if (knownRemainingMinutes == 0) {
                                0.0
                            } else {
                                -knownRemainingMinutes.toDouble()
                            }
                            pacingState = if (knownRemainingMinutes == 0 ||
                                (capacityMarginMinutes ?: -1.0) >= 0.0
                            ) {
                                CollectionPacingState.ON_TRACK
                            } else {
                                CollectionPacingState.AT_RISK
                            }
                            if (
                                pacingState == CollectionPacingState.AT_RISK &&
                                deadline.isAfter(today) &&
                                remainingMinutes != null &&
                                remainingMinutes > 0
                            ) {
                                estimatedFitDate = pace.earliestFitDate(
                                    startDate = today.plusDays(1),
                                    requiredMinutes = knownRemainingMinutes,
                                )
                            }
                        }
                    }
                }
            }
            CollectionMode.COMPLETION_GOAL -> {
                pacingState = when {
                    unknownDurationCount > 0 -> CollectionPacingState.INCOMPLETE_DATA
                    personalPace == null || personalPace.confidence != PersonalPaceConfidence.RELIABLE -> {
                        CollectionPacingState.LEARNING
                    }
                    else -> CollectionPacingState.ON_TRACK
                }
                if (
                    pacingState == CollectionPacingState.ON_TRACK &&
                    remainingMinutes != null &&
                    remainingMinutes > 0
                ) {
                    completionHorizonDate = personalPace?.earliestFitDate(
                        startDate = today.plusDays(1),
                        requiredMinutes = remainingMinutes,
                    )
                }
            }
            CollectionMode.ORDERED_QUEUE -> {
                pacingState = when {
                    unknownDurationCount > 0 -> CollectionPacingState.INCOMPLETE_DATA
                    personalPace == null || personalPace.confidence != PersonalPaceConfidence.RELIABLE -> {
                        CollectionPacingState.LEARNING
                    }
                    else -> CollectionPacingState.ON_TRACK
                }
                if (personalPace?.confidence == PersonalPaceConfidence.RELIABLE) {
                    val nextRemaining = nextUp
                        ?.estimateMinutes(CollectionTimeBasis.COMPLETIONIST)
                        ?.takeIf { it > 0 }
                        ?.let { (it - nextUp.playtimeMinutes).coerceAtLeast(0) }
                    if (nextRemaining != null && nextRemaining > 0) {
                        nextGameHorizonDate = personalPace?.earliestFitDate(
                            startDate = today.plusDays(1),
                            requiredMinutes = nextRemaining,
                        )
                    }
                    if (remainingMinutes != null && remainingMinutes > 0 && unknownDurationCount == 0) {
                        queueHorizonDate = personalPace?.earliestFitDate(
                            startDate = today.plusDays(1),
                            requiredMinutes = remainingMinutes,
                        )
                    }
                }
            }
        }

        val deadlineInterventionEligible = mode == CollectionMode.DEADLINE_GOAL &&
            targetDate != null &&
            (unknownDurationCount > 0 || (remainingMinutes ?: 0) > 0) &&
            (deadlinePassed || pacingState == CollectionPacingState.AT_RISK)

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
            timeDifferentialMinutes = capacityMarginMinutes?.toInt(),
            unknownDurationCount = unknownDurationCount,
            nextUp = nextUp,
            nextUpPosition = nextUp?.let { nextUpIndex + 1 },
            queueCompleted = queueCompleted,
            empty = false,
                paceConfidence = personalPace?.confidence
                    ?.takeUnless { mode == CollectionMode.BASIC },
            pacingState = pacingState,
            projectedCapacityMinutes = projectedCapacityMinutes,
            projectedActiveDays = projectedActiveDays,
            recentTrackedPaceMinutes = recentTrackedPaceMinutes,
            requiredMinutesPerActiveDay = requiredMinutesPerActiveDay,
            capacityMarginMinutes = capacityMarginMinutes,
            deadlineInterventionEligible = deadlineInterventionEligible,
            estimatedFitDate = estimatedFitDate,
            completionHorizonDate = completionHorizonDate,
            nextGameHorizonDate = nextGameHorizonDate,
            queueHorizonDate = queueHorizonDate,
            pacingForecast = pacingForecast,
        )
    }

    private fun timeBasisFor(mode: CollectionMode, selected: CollectionTimeBasis): CollectionTimeBasis =
        when (mode) {
            CollectionMode.DEADLINE_GOAL -> selected
            CollectionMode.COMPLETION_GOAL,
            CollectionMode.ORDERED_QUEUE,
            -> CollectionTimeBasis.COMPLETIONIST
            CollectionMode.BASIC -> selected
        }
}
