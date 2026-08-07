package com.example.backlogium.ui.collections

import com.example.backlogium.domain.CollectionBanner
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionPacingState
import kotlin.math.ceil

enum class DeadlineUrgency {
    NORMAL,
    SOON,
    DUE_OR_PAST,
}

/** Small presentation decisions kept pure so the overview can be tested without Compose. */
fun collectionPacingStateLabel(state: CollectionPacingState?): String? = when (state) {
    CollectionPacingState.ON_TRACK -> "On track"
    CollectionPacingState.AT_RISK -> "At risk"
    CollectionPacingState.LEARNING -> "Learning"
    CollectionPacingState.INCOMPLETE_DATA -> "Incomplete"
    null -> null
}

fun collectionModePacingSectionVisible(mode: CollectionMode): Boolean =
    mode != CollectionMode.BASIC

fun collectionDeadlineActionVisible(banner: CollectionBanner): Boolean =
    banner.deadlineInterventionEligible

fun deadlineUrgency(daysRemaining: Long?): DeadlineUrgency = when {
    daysRemaining == null || daysRemaining > DEADLINE_SOON_DAYS -> DeadlineUrgency.NORMAL
    daysRemaining <= 0L -> DeadlineUrgency.DUE_OR_PAST
    else -> DeadlineUrgency.SOON
}

/**
 * A provisional workload fallback for learning profiles. It deliberately describes active play
 * days rather than a calendar fit, so it remains useful without claiming the deadline is safe or
 * infeasible before enough history has been observed.
 */
fun learningDeadlineActiveDaysNeeded(
    remainingMinutes: Int?,
    recentTrackedPaceMinutes: Double?,
): Int? {
    val remaining = remainingMinutes?.takeIf { it > 0 } ?: return null
    val pace = recentTrackedPaceMinutes?.takeIf { it > 0.0 } ?: return null
    return ceil(remaining / pace).toInt().takeIf { it > 0 }
}

private const val DEADLINE_SOON_DAYS = 7L
