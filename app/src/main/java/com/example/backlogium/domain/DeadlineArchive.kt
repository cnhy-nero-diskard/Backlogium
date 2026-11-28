package com.example.backlogium.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable
data class DeadlineCollectionIdentity(val originId: Long, val createdAt: Long)

@Serializable
enum class DeadlineOutcome { ESTIMATED_MET, ESTIMATED_NOT_MET, UNDETERMINED }

@Serializable
enum class DeadlineEvidenceGap {
    GOAL_HISTORY, MEMBERSHIP_HISTORY, VISIBILITY_HISTORY, EMPTY_MEMBERSHIP, ALL_MEMBERS_HIDDEN,
    ESTIMATE, PLAYTIME, CUTOFF_AMBIGUITY,
}

@Serializable
data class DeadlinePlayEvidence(
    val creditedMinutes: Long,
    val observedBefore: Long,
    val observedAfter: Long,
)

/** Trusted Steam cumulative readings bound the cutoff quantity without claiming it is exact. */
@Serializable
data class DeadlinePlayBounds(
    val lowerBoundMinutes: Long,
    val upperBoundMinutes: Long? = null,
    val observedBefore: Long,
    val observedAfter: Long? = null,
)

/** A conservative lower bound from canonical, dated, closed sessions before the cutoff. */
@Serializable
data class DeadlineSessionPlayEvidence(
    val creditedMinutes: Long,
    val sessionCount: Int,
    val firstSessionAt: Long,
    val lastSessionEndAt: Long,
)

@Serializable
data class DeadlineArchivedMember(
    val appId: Long,
    val name: String?,
    val hiddenAtCutoff: Boolean?,
    val membershipObservedAt: Long,
    val estimateMinutes: Int?,
    val play: DeadlinePlayEvidence?,
    val recordedSessions: Int?,
    val recordedTrophies: Int?,
    val sessionPlay: DeadlineSessionPlayEvidence? = null,
    val playBounds: DeadlinePlayBounds? = null,
) {
    val remainingMinutes: Long?
        get() = estimateMinutes?.let { estimate ->
            when {
                play != null -> (estimate - play.creditedMinutes).coerceAtLeast(0)
                playBounds?.lowerBoundMinutes?.let { it >= estimate } == true -> 0L
                sessionPlay?.creditedMinutes?.let { it >= estimate } == true -> 0L
                else -> null
            }
        }
}

@Serializable
data class DeadlineArchive(
    val identity: DeadlineCollectionIdentity,
    val name: String,
    val targetDate: String,
    val zone: String,
    val timeBasis: CollectionTimeBasis,
    val cutoff: Long,
    val finalizedAt: Long,
    val goalObservedAt: Long?,
    val membershipKnown: Boolean,
    val members: List<DeadlineArchivedMember>,
    val cutoffAmbiguous: Boolean = false,
    @Required val outcome: DeadlineOutcome = assessDeadline(
        goalObservedAt, membershipKnown, members, cutoffAmbiguous,
    ),
) {
    val evaluatedMembers: List<DeadlineArchivedMember>
        get() = members.filter { it.hiddenAtCutoff == false }

    val gaps: Set<DeadlineEvidenceGap>
        get() = buildSet {
            if (goalObservedAt == null) add(DeadlineEvidenceGap.GOAL_HISTORY)
            if (!membershipKnown) add(DeadlineEvidenceGap.MEMBERSHIP_HISTORY)
            if (members.any { it.hiddenAtCutoff == null }) add(DeadlineEvidenceGap.VISIBILITY_HISTORY)
            if (membershipKnown && members.isEmpty()) add(DeadlineEvidenceGap.EMPTY_MEMBERSHIP)
            if (members.isNotEmpty() && members.all { it.hiddenAtCutoff == true }) {
                add(DeadlineEvidenceGap.ALL_MEMBERS_HIDDEN)
            }
            if (evaluatedMembers.any { it.estimateMinutes == null }) add(DeadlineEvidenceGap.ESTIMATE)
            if (evaluatedMembers.any { member ->
                    val estimate = member.estimateMinutes
                    estimate != null && !member.provesMet(estimate) && !member.provesShortfall(estimate)
                }
            ) add(DeadlineEvidenceGap.PLAYTIME)
            if (cutoffAmbiguous) add(DeadlineEvidenceGap.CUTOFF_AMBIGUITY)
        }

}

internal fun assessDeadline(
    goalObservedAt: Long?,
    membershipKnown: Boolean,
    members: List<DeadlineArchivedMember>,
    cutoffAmbiguous: Boolean = false,
): DeadlineOutcome {
    if (cutoffAmbiguous || goalObservedAt == null || !membershipKnown || members.any { it.hiddenAtCutoff == null }) {
        return DeadlineOutcome.UNDETERMINED
    }
    val evaluated = members.filter { it.hiddenAtCutoff == false }
    return when {
        evaluated.any { member -> member.estimateMinutes?.let(member::provesShortfall) == true } ->
            DeadlineOutcome.ESTIMATED_NOT_MET
        evaluated.isNotEmpty() && evaluated.all { member ->
            member.estimateMinutes?.let(member::provesMet) == true
        } -> DeadlineOutcome.ESTIMATED_MET
        else -> DeadlineOutcome.UNDETERMINED
    }
}

private fun DeadlineArchivedMember.provesMet(estimate: Int): Boolean =
    play?.creditedMinutes?.let { it >= estimate } == true ||
        playBounds?.lowerBoundMinutes?.let { it >= estimate } == true ||
        sessionPlay?.creditedMinutes?.let { it >= estimate } == true

private fun DeadlineArchivedMember.provesShortfall(estimate: Int): Boolean =
    play?.creditedMinutes?.let { it < estimate } == true ||
        playBounds?.upperBoundMinutes?.let { it < estimate } == true

fun deadlineCutoff(targetDate: String, zone: String): Long =
    LocalDate.parse(targetDate).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
