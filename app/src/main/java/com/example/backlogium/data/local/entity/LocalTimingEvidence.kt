package com.example.backlogium.data.local.entity

import androidx.room.Entity

/**
 * Lightweight local observed timing facts kept by the live observer (recover-missed-playtime,
 * design.md Decision 5). These rows describe when the app's own polling was active and known-good
 * for a game; they supply *placement* evidence only and never authorize owned-game quantity.
 *
 * An isolated current-presence sample or a single end event is not continuous interval evidence,
 * so [observedUntil] alone never manufactures an interval. A process/network gap is preserved via
 * [continuityLossFrom]/[continuityLossRecoveredAt] rather than bridged by the live timer.
 *
 * [windowStart] is the retained acquisition window start the observer last proved; a reader rewind
 * must not shorten the usable window on failure.
 */
@Entity(
    tableName = "recovery_local_evidence",
    primaryKeys = ["account", "appId", "generation"],
)
data class LocalTimingEvidence(
    val account: String,
    val appId: Long,
    /** Process/schedule generation, so a fresh observer never trusts a stale predecessor's rows. */
    val generation: Long,
    /** First verified local observation instant; null until one exists. */
    val firstSampleAt: Long? = null,
    /** Last verified local observation instant; null until one exists. */
    val lastSuccessAt: Long? = null,
    /** Latest instant the observer knows the app was actually present; an end event alone is not an interval. */
    val observedUntil: Long? = null,
    /** Whether the observer currently lacks coverage, and since when. */
    val continuityLossFrom: Long? = null,
    /** When the observer's coverage was last restored. */
    val continuityLossRecoveredAt: Long? = null,
    /** Retained acquisition window start; never shortened by an isolated failure. */
    val windowStart: Long,
    val updatedAt: Long,
)