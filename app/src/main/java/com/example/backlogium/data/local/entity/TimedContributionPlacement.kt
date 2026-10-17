package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A funded share of a contribution placed against an actual timestamped session because validated
 * timing evidence covered part of its observation window (recover-missed-playtime, design.md
 * Decision 2 and 5).
 *
 * This row does not earn XP on its own — the contribution already holds the authorized quantity —
 * it only documents where that quantity lives. A session may hold shares from several
 * contributions; each share is a separate row linked to its own contribution.
 *
 * [placementId] is derived from the contribution, revision, evidence identity, credited date and
 * session, so replaying the same plan is idempotent ([superseded] is flipped by a later revision,
 * never by a replay).
 */
@Entity(
    tableName = "recovery_timed_placements",
    primaryKeys = ["placementId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaytimeContribution::class,
            parentColumns = ["contributionId"],
            childColumns = ["contributionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["contributionId"]),
        Index(value = ["account", "appId"]),
        Index(value = ["sessionId"]),
    ],
)
data class TimedContributionPlacement(
    val placementId: String,
    val contributionId: String,
    val account: String,
    val appId: Long,
    val revision: Long,
    /** The session row the share was placed into; null only for a retained-but-detached share. */
    val sessionId: Long? = null,
    /** Positive funded share for this placement; `minutes > 0` is enforced by the writer. */
    val minutes: Long,
    /** Frozen attribution date: the local date (in [zone]) the session's start credits to. */
    val creditedDate: String,
    val zone: String,
    /** Identity of the validated timing evidence that informed this placement, when any. */
    val evidenceIdentity: String? = null,
    val evidenceQuality: String = RecoveryEvidenceQuality.ESTIMATE,
    /**
     * The game's hidden status and Focus/goal status *at credit time*, frozen so a later
     * representation-only move (late-evidence re-placement) re-computes old and new dates under
     * the same daily eligibility without consulting today's settings.
     */
    val hiddenAtCredit: Boolean = false,
    val goalAtCredit: Boolean = false,
    val superseded: Boolean = false,
    /** Manually protected; a later automatic upgrade must never replace this placement. */
    val protected: Boolean = false,
    val createdAt: Long,
)

/** Where a placement's timing came from; never implies a separate quantity credit. */
object RecoveryEvidenceQuality {
    const val LOCAL = "LOCAL"
    const val CLOUD = "CLOUD"
    const val ESTIMATE = "ESTIMATE"
}