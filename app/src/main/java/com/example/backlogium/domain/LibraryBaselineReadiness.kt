package com.example.backlogium.domain

/**
 * Whether the active account's owned-library baseline is durably confirmed and usable.
 *
 * Readiness is about account-scoped confirmation evidence, not about the number of game rows: a
 * committed zero-game baseline confirms readiness exactly like a populated one. It can never be
 * manufactured from credentials, nonempty local rows, a generic/restored `lastSyncAt`, scheduler
 * success alone, or a later failed refresh.
 */
sealed interface LibraryBaselineReadiness {
    /**
     * An accepted owned-library response committed for the active account, and no account reset is
     * pending. Covers explicitly confirmed empty libraries.
     */
    data object Confirmed : LibraryBaselineReadiness

    /**
     * No durable same-account baseline evidence: an accepted poll — or, where the UI offers one,
     * explicit recovery — is required. Restored and legacy rows stay here until a valid poll
     * confirms them.
     */
    data object Unknown : LibraryBaselineReadiness
}