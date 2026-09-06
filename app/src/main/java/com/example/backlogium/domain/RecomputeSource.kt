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
     * A player-initiated removal of a Family Shared game's tracked history, or the reversal of
     * one (auditfix-session-ledger-integrity, #104) — bookkeeping performed in a settings
     * surface, not play. Both directions can move derived values, exactly like [RESTORE] can,
     * and neither is progress the player earned. `add-hidden-games` (0/55 tasks) adds its own
     * source for hide/unhide alongside this one rather than widening it — the two are both
     * administrative and both non-earned, but a reader tracing a baseline reseed should still be
     * able to tell a removal from a hide.
     */
    GAME_REMOVAL,

    /**
     * A corrective recompute replacing a `totalXp` an Int-overflow bug had wrapped to `0`
     * (auditfix-session-ledger-integrity, #114) — see [com.example.backlogium.data.local.entity
     * .PlayerProfile.pendingXpIntegrityCorrection]. The resulting jump is real accumulated XP,
     * not newly earned progress, so it must reseed the baseline exactly like the other non-SYNC
     * sources rather than fire a cascade of level-up events for play that happened long ago.
     */
    XP_INTEGRITY_CORRECTION,
}
