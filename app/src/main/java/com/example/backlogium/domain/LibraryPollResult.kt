package com.example.backlogium.domain

/**
 * The domain outcome of one admitted library poll, attributable to the exact work operation and
 * account that requested it.
 *
 * This is the compact classification setup and other consumers project a poll against. It is
 * deliberately independent of WorkManager scheduler success and of the profile's latest error
 * column: a job that finished without an accepted, committed owned-library response is never
 * [Committed].
 */
sealed interface LibraryPollResult {
    /**
     * The poll's requested operation committed: an accepted owned-library response was persisted
     * for this work/account, and the poll may be reported as a successful library import.
     *
     * A [gameCount] of zero is the explicitly confirmed empty library — Steam's own `game_count:
     * 0` accepted and committed — which is a successful empty-library baseline rather than a
     * privacy failure.
     */
    data class Committed(
        /** Number of owned games accepted and committed. Zero is a confirmed empty baseline. */
        val gameCount: Int,
        /** The `lastSyncAt` the accepted response committed, for investigation. */
        val lastSyncAt: Long,
    ) : LibraryPollResult {
        /** True only when Steam explicitly confirmed an empty library and it was committed. */
        val confirmedEmpty: Boolean get() = gameCount == 0
    }

    /** The operation deliberately did not perform its requested library poll. */
    sealed interface NotPerformed : LibraryPollResult {
        /** The worker exited because no credentials were available. */
        data object MissingCredentials : NotPerformed

        /** The account admission boundary refused the poll for the active account. */
        data object AccountAdmissionRefused : NotPerformed

        /**
         * The response was empty or unreadable and Steam did not explicitly confirm the zero
         * count — typically a private profile. Last-good data is retained and no library-sync
         * success may be claimed.
         */
        data object UnconfirmedEmpty : NotPerformed
    }

    /**
     * The operation failed in a way a consumer must offer recovery for.
     *
     * Transient retry *scheduling* is deliberately not this kind: a poll WorkManager has merely
     * re-queued has no terminal outcome, so it projects to [Unknown] until a terminal outcome
     * exists. Retry scheduled is pending, never terminally failed.
     */
    data class RecoverableFailure(
        /** User-facing explanation of what failed and that a re-run is the recovery path. */
        val reason: String,
    ) : LibraryPollResult

    /**
     * A requested or finished operation whose outcome cannot be established from domain evidence —
     * a legacy completed job without a persisted domain outcome, a pruned scheduling record, a
     * still-pending operation, or evidence bound to a different work/account. Never [Committed] and
     * never reported as a successful library import.
     */
    data object Unknown : LibraryPollResult
}