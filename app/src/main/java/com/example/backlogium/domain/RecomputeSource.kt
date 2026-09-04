package com.example.backlogium.domain

/**
 * Why a set of derived gamification values is being persisted.
 *
 * Only [SYNC] represents progress earned through play. The remaining sources may legitimately
 * move derived values in either direction, but they redefine the presentation baseline rather
 * than producing player-facing progress events.
 */
enum class RecomputeSource {
    SYNC,
    RULE_CHANGE,
    BACKFILL,
    RESTORE,

    /**
     * A corrective recompute replacing a `totalXp` an Int-overflow bug had wrapped to `0`
     * (auditfix-session-ledger-integrity, #114) — see [com.example.backlogium.data.local.entity
     * .PlayerProfile.pendingXpIntegrityCorrection]. The resulting jump is real accumulated XP,
     * not newly earned progress, so it must reseed the baseline exactly like the other non-SYNC
     * sources rather than fire a cascade of level-up events for play that happened long ago.
     */
    XP_INTEGRITY_CORRECTION,
}
