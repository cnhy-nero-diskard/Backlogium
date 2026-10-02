package com.example.backlogium.data.repo

import com.example.backlogium.domain.LibraryBaselineReadiness
import com.example.backlogium.domain.LibraryPollResult

/** Identity of one admitted library-poll operation: the durable pair every result is attributed to. */
data class LibraryPollRequest(
    /** Exact admitted work UUID or durable operation-record key; never a reusable unique work name. */
    val workIdentity: String,
    /** The Steam account the operation ran for. */
    val accountSteamId: String,
)

/** Repository-boundary reasons a poll did not continue to a commit. */
enum class LibraryPollRefusal {
    /** The worker exited because no credentials were available. */
    MISSING_CREDENTIALS,
    /** The account admission boundary refused the poll for the active account. */
    ACCOUNT_ADMISSION_REFUSED,
    /** The response was empty or unreadable without an explicit `game_count: 0`. */
    UNCONFIRMED_EMPTY,
}

/**
 * Repository-boundary evidence about one poll, before domain classification.
 *
 * Every record bound to a poll carries the exact work/account pair it belongs to; the mapper in
 * [LibraryPollRepository] refuses to project a record onto a different request, so another
 * operation's outcome can never classify this one.
 */
sealed interface LibraryPollEvidence {
    /** The poll's raw library transaction committed an accepted response for that work/account. */
    data class Committed(
        val workIdentity: String,
        val accountSteamId: String,
        val gameCount: Int,
        val lastSyncAt: Long,
    ) : LibraryPollEvidence

    /** The poll exited without committing, with an attributable refusal. */
    data class NotPerformed(
        val workIdentity: String,
        val accountSteamId: String,
        val refusal: LibraryPollRefusal,
    ) : LibraryPollEvidence

    /** The poll failed before committing; the operation can be retried or recovered. */
    data class Failed(
        val workIdentity: String,
        val accountSteamId: String,
        val reason: String,
    ) : LibraryPollEvidence

    /**
     * The operation has no terminal outcome yet — queued, running, or retry-scheduled. This is
     * never a [LibraryPollResult.RecoverableFailure]: retry scheduling is not terminal failure.
     */
    data object Pending : LibraryPollEvidence
}

/** How stored account-scoped baseline evidence got here; only [LOCAL] grants readiness. */
enum class LibraryBaselineProvenance {
    /** Written in the accepted raw library transaction of a poll for this account. */
    LOCAL,

    /** Imported by a backup restore; must not be trusted as freshly confirmed readiness. */
    RESTORED,

    /** Predates baseline evidence (legacy/migrated rows); conservatively unconfirmed. */
    LEGACY,
}

/**
 * Account-scoped evidence that an accepted owned-library response committed for [steamId].
 *
 * Deliberately not the profile's generic `lastSyncAt`: that timestamp already exists and is not
 * readiness evidence, and a restored or legacy row must not become a baseline. The explicit
 * [provenance] is what lets restored data be told apart from local confirmation.
 */
data class LibraryBaselineEvidence(
    val steamId: String,
    val confirmedAt: Long,
    val provenance: LibraryBaselineProvenance,
)

/**
 * Pure boundary mapper for library-poll outcomes and baseline readiness.
 *
 * The mappers classify repository-boundary evidence into the domain models and enforce
 * attribution — evidence bound to another work operation or account can never be projected onto
 * this request, and no generic/restored timestamp can become readiness.
 */
class LibraryPollRepository {

    /**
     * Project one admitted poll request onto its attributable domain result.
     *
     * Attribution is enforced for every evidence kind: only evidence carrying the same
     * [LibraryPollRequest.workIdentity] and [LibraryPollRequest.accountSteamId] as the request may
     * classify it. Anything else — a different operation's outcome, a different account's commit,
     * a merely pending record, or no record at all — projects to [LibraryPollResult.Unknown] and
     * can never fabricate a [LibraryPollResult.Committed].
     */
    fun resultFor(
        request: LibraryPollRequest,
        evidence: LibraryPollEvidence?,
    ): LibraryPollResult {
        if (request.workIdentity.isBlank() || request.accountSteamId.isBlank()) {
            return LibraryPollResult.Unknown
        }
        return when (evidence) {
            null -> LibraryPollResult.Unknown
            LibraryPollEvidence.Pending -> LibraryPollResult.Unknown
            is LibraryPollEvidence.Committed ->
                if (!attributable(evidence, request) || evidence.gameCount < 0) {
                    LibraryPollResult.Unknown
                } else {
                    LibraryPollResult.Committed(
                        gameCount = evidence.gameCount,
                        lastSyncAt = evidence.lastSyncAt,
                    )
                }
            is LibraryPollEvidence.NotPerformed ->
                if (!attributable(evidence, request)) {
                    LibraryPollResult.Unknown
                } else {
                    when (evidence.refusal) {
                        LibraryPollRefusal.MISSING_CREDENTIALS ->
                            LibraryPollResult.NotPerformed.MissingCredentials
                        LibraryPollRefusal.ACCOUNT_ADMISSION_REFUSED ->
                            LibraryPollResult.NotPerformed.AccountAdmissionRefused
                        LibraryPollRefusal.UNCONFIRMED_EMPTY ->
                            LibraryPollResult.NotPerformed.UnconfirmedEmpty
                    }
                }
            is LibraryPollEvidence.Failed ->
                if (!attributable(evidence, request)) {
                    LibraryPollResult.Unknown
                } else {
                    LibraryPollResult.RecoverableFailure(reason = evidence.reason)
                }
        }
    }

    /**
     * Whether the active account's owned-library baseline is durably confirmed.
     *
     * Confirmed requires all of: an active account with no pending reset, and
     * [LibraryBaselineProvenance.LOCAL] evidence for exactly that account. A generic or restored
     * `lastSyncAt`, nonempty local game rows, credentials, scheduler success, a different account's
     * confirmation, or a later failed refresh cannot manufacture readiness.
     */
    fun readinessFor(
        activeSteamId: String?,
        pendingResetSteamId: String?,
        evidence: LibraryBaselineEvidence?,
    ): LibraryBaselineReadiness {
        val active = activeSteamId?.takeIf { it.isNotBlank() }
            ?: return LibraryBaselineReadiness.Unknown
        if (pendingResetSteamId != null) return LibraryBaselineReadiness.Unknown
        val baseline = evidence ?: return LibraryBaselineReadiness.Unknown
        if (baseline.provenance != LibraryBaselineProvenance.LOCAL) {
            return LibraryBaselineReadiness.Unknown
        }
        if (baseline.steamId != active) return LibraryBaselineReadiness.Unknown
        return LibraryBaselineReadiness.Confirmed
    }

    private fun attributable(
        evidence: LibraryPollEvidence,
        request: LibraryPollRequest,
    ): Boolean = when (evidence) {
        is LibraryPollEvidence.Committed ->
            evidence.workIdentity == request.workIdentity &&
                evidence.accountSteamId == request.accountSteamId
        is LibraryPollEvidence.NotPerformed ->
            evidence.workIdentity == request.workIdentity &&
                evidence.accountSteamId == request.accountSteamId
        is LibraryPollEvidence.Failed ->
            evidence.workIdentity == request.workIdentity &&
                evidence.accountSteamId == request.accountSteamId
        LibraryPollEvidence.Pending -> false
    }
}
