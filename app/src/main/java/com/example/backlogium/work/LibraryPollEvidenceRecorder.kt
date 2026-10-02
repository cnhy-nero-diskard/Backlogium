package com.example.backlogium.work

import com.example.backlogium.data.local.dao.LibraryPollEvidenceDao
import com.example.backlogium.data.local.entity.LibraryPollEvidenceRecord
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.repo.LibraryPollRefusal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The exact production seam [SteamSyncWorker] uses for every attributable library-poll outcome
 * (stabilize-first-run-setup). It owns the one policy a durable operation record must enforce:
 *
 * - **Latest per (work id, account)**: each outcome replaces the pair's previous row, so the table
 *   never grows per poll. **Bounded second-work history**: [pruneOlderThanRetained] additionally
 *   caps how many distinct work identities' terminal outcomes are retained per account, keeping the
 *   current work, every protected (setup-observed) association, and the newest outcomes.
 * - **A committed record is authoritative**: the accepted owned-library response is durable (the
 *   row is written inside the raw library transaction), so no later [recordNotPerformed] or
 *   [recordFailure] of the same work may regress it — including the crash-after-raw-commit window
 *   in which a retry of the same work identity fails before its own commit. Only a newer committed
 *   poll replaces a committed record. Readiness is never inferred from these rows; the profile
 *   confirmation columns are the readiness source.
 *
 * The worker serializes every non-committed call under the account-admission barrier
 * ([SteamSyncCoordinator]), and a single work identity runs one attempt at a time, so the
 * read-then-write guard here races neither another attempt nor the identity reset it describes.
 */
@Singleton
class LibraryPollEvidenceRecorder @Inject constructor(
    private val evidenceDao: LibraryPollEvidenceDao,
) {

    /**
     * Records a committed outcome for the admitted work/account. Call **inside** the caller's raw
     * library transaction so a crash after that transaction — and before any worker output — leaves
     * the committed effect recoverable from Room, while a rollback of the raw commit discards it
     * with the library. `gameCount == 0` is the explicitly confirmed empty library.
     */
    suspend fun recordCommitted(
        workIdentity: String,
        accountSteamId: String,
        gameCount: Int,
        committedAt: Long,
    ) {
        evidenceDao.upsert(
            LibraryPollEvidenceRecord(
                workIdentity = workIdentity,
                accountSteamId = accountSteamId,
                outcome = LibraryPollOutcomeKind.COMMITTED.name,
                gameCount = gameCount,
                lastSyncAt = committedAt,
                refusal = null,
                reason = null,
                recordedAt = committedAt,
            ),
        )
    }

    /**
     * Records a not-performed outcome (missing credentials, account admission refusal, or an
     * unconfirmed empty/private response). Returns `false` — and writes nothing — when the pair
     * already has a durable [COMMITTED] record: the committed effect is authoritative.
     */
    suspend fun recordNotPerformed(
        workIdentity: String,
        accountSteamId: String,
        refusal: LibraryPollRefusal,
        recordedAt: Long,
    ): Boolean {
        if (hasCommitted(workIdentity, accountSteamId)) return false
        evidenceDao.upsert(
            LibraryPollEvidenceRecord(
                workIdentity = workIdentity,
                accountSteamId = accountSteamId,
                outcome = LibraryPollOutcomeKind.NOT_PERFORMED.name,
                gameCount = -1,
                lastSyncAt = 0L,
                refusal = refusal.name,
                reason = null,
                recordedAt = recordedAt,
            ),
        )
        return true
    }

    /**
     * Records a failed outcome under the same durable committed protection. A retry of the same
     * work identity that fails before its own commit must not overwrite the committed record of the
     * effect an earlier attempt already accepted — the local run state cannot protect that across
     * attempts or process death; the durable record can. Returns `false` when the committed record
     * was retained instead.
     */
    suspend fun recordFailure(
        workIdentity: String,
        accountSteamId: String,
        reason: String,
        recordedAt: Long,
    ): Boolean {
        if (hasCommitted(workIdentity, accountSteamId)) return false
        evidenceDao.upsert(
            LibraryPollEvidenceRecord(
                workIdentity = workIdentity,
                accountSteamId = accountSteamId,
                outcome = LibraryPollOutcomeKind.FAILED.name,
                gameCount = -1,
                lastSyncAt = 0L,
                refusal = null,
                reason = reason,
                recordedAt = recordedAt,
            ),
        )
        return true
    }

    /**
     * Bounded retention to stop this store from becoming an unbounded second work history
     * (stabilize-first-run-setup design, decision 4): repeated manual jobs and expired work
     * identities would otherwise leave one row each forever. This deletes the account's **older
     * terminal outcomes** beyond a small cap while always keeping:
     *
     * - the [currentWorkId]'s row (the work this attempt just ran), and
     * - every [protectedWorkIds] association (the setup store's currently-observed stages, so
     *   pending setup work is never pruned before its association is resolved).
     *
     * The protected snapshot must be captured **outside any Room transaction** (the setup store is
     * DataStore-backed) and passed in; this method performs a single DAO delete statement, so it is
     * atomic and may run outside a transaction. It never writes, so a capture failure or a
     * non-committed outcome can never replace a durable COMMITTED record — pruning can only remove
     * an older terminal row that is beyond the cap.
     *
     * @return the number of rows pruned.
     *
     * Delegates to a single DAO delete query, `LibraryPollEvidenceDao.pruneOlderThanRetained` (the
     * history agent adds it under the parent's coordination; it must exactly match the recorder's
     * call: account/current/protected plus the newest-[retainedCount] keep-set).
     */
    suspend fun pruneOlderThanRetained(
        accountSteamId: String,
        currentWorkId: String,
        protectedWorkIds: Set<String>,
        retainedCount: Int = RETAINED_OUTCOMES_PER_ACCOUNT,
    ): Int {
        if (accountSteamId.isBlank() || currentWorkId.isBlank() || retainedCount < 0) return 0
        return evidenceDao.pruneOlderThanRetained(
            accountSteamId = accountSteamId,
            currentWorkId = currentWorkId,
            // Room expands an empty IN-list into invalid `NOT IN ()`, so an empty protected set
            // passes an impossible sentinel instead: no real work UUID can match it.
            protectedWorkIds = protectedWorkIds.ifEmpty { setOf(NO_PROTECTED_WORK_SENTINEL) }.toList(),
            retainedCount = retainedCount,
        )
    }

    private suspend fun hasCommitted(workIdentity: String, accountSteamId: String): Boolean =
        evidenceDao.getFor(workIdentity, accountSteamId)?.outcome == LibraryPollOutcomeKind.COMMITTED.name

    companion object {
        /** Small cap: how many of the account's most recent terminal outcomes are retained. */
        const val RETAINED_OUTCOMES_PER_ACCOUNT = 20

        /**
         * Sentinel for an empty protected set. A real admitted work UUID can never equal this, so
         * `NOT IN ('<no-protected-work>')` is a semantic no-op.
         */
        const val NO_PROTECTED_WORK_SENTINEL = "<no-protected-work>"
    }
}