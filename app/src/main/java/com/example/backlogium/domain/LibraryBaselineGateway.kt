package com.example.backlogium.domain

/**
 * Narrow, JVM-testable window onto the durable import facts the first-run journey surfaces render:
 * baseline readiness, the one-time import receipt, and pending-recompute recovery. Implemented by
 * the profile repository and bound in AppModule so the journey coordinator stays testable on the
 * JVM.
 *
 * Deliberately a UI-facts seam. Journey phase transitions are never inferred from these — the
 * shared import coordinator's attributable results decide phases; these only drive what a surface
 * may offer (Import enabled, Continue instead of a second import, recovery pending).
 */
interface LibraryBaselineGateway {

    /** A confirmed same-account library baseline exists (the Import affordance's enablement). */
    suspend fun baselineConfirmed(): Boolean

    /** The one-time import has committed for [steamId] (the "Already imported" receipt). */
    suspend fun importBackfilled(steamId: String): Boolean

    /**
     * Whether the one-time history import committed for [steamId] but its recompute is unfinished.
     * Gated on the history flag: a bare backup RESTORE marker on a never-imported profile reads as
     * "needs derive", not "historical minutes saved". Shown on cold launch without relying on the
     * in-process Running/Completed state.
     */
    suspend fun recomputePending(steamId: String): Boolean
}