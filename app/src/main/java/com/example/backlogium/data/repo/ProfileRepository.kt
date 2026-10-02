package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.LibraryPollEvidenceDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.LibraryPollEvidenceRecord
import com.example.backlogium.data.local.entity.LibraryPollOutcomeKind
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.history.HistoryImportRequestRecord
import com.example.backlogium.domain.HistoryImportCoordinator
import com.example.backlogium.domain.HistoryImportResult
import com.example.backlogium.domain.HistoryImportState
import com.example.backlogium.domain.LibraryBaselineGateway
import com.example.backlogium.domain.LibraryBaselineReadiness
import com.example.backlogium.domain.LibraryPollResult
import com.example.backlogium.domain.PlaytimeBackfillResetResult
import com.example.backlogium.work.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The engine's persisted outputs plus sync status, as consumers see them. */
data class PlayerStats(
    val steamId: String,
    val steamLevel: Int,
    val totalXp: Long,
    val level: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val lastSyncAt: Long,
    val lastSyncError: String?,
    /** True once the player has opted in to importing historical Steam playtime (one-time). */
    val playtimeBackfilled: Boolean,
    /** Steam persona name, or null before the first sync observed one. */
    val personaName: String?,
    /** Full-size Steam avatar URL, or null before the first sync observed one. */
    val avatarUrl: String?,
    /**
     * True while a history raw import is committed but its recompute is unfinished. Gated on the
     * one-time flag so a bare backup RESTORE marker on a never-imported profile reads as "needs
     * derive", not "historical minutes saved". Durable, so a cold launch can show recovery without
     * an in-process Running/Completed state.
     */
    val pendingHistoryRecompute: Boolean = false,
    /** The explicit request behind a pending history recompute, when one is recorded (BACKFILL). */
    val pendingHistoryRequestId: String? = null,
)

/** Per-day play totals keyed by local calendar date (ISO-8601 "yyyy-MM-dd"). */
data class DayProgress(
    val date: String,
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
    val questMet: Boolean,
)

/** Profile aggregates, per-day stats, the manual "Sync now" trigger, and history import. */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: PlayerProfileDao,
    private val dailyProgressDao: DailyProgressDao,
    private val syncScheduler: SyncScheduler,
    private val historyImport: HistoryImportCoordinator,
    private val credentials: CredentialsProvider,
    private val accountChangeMarker: AccountChangeMarkerStore,
    private val evidenceDao: LibraryPollEvidenceDao,
) : LibraryBaselineGateway {
    /** Pure boundary projection for attributable library-poll outcomes and baseline readiness. */
    private val pollMapper = LibraryPollRepository()
    val profile: Flow<PlayerStats?> = profileDao.observe().map { it?.toDomain() }
    val dailyProgress: Flow<List<DayProgress>> = dailyProgressDao.observeAll()
        .map { rows -> rows.map(DailyProgress::toDomain) }

    /**
     * True while any Steam poll — scheduled or manual — is in flight (WorkManager-backed), held
     * briefly past completion so a fast sync stays perceptible.
     */
    val syncInProgress: Flow<Boolean> = syncScheduler.syncInProgress

    /** True while a reconciliation pass — deferred or forced — is enqueued or running. */
    val reconciliationInProgress: Flow<Boolean> = syncScheduler.reconciliationInProgress

    /** True while the shared history import is admitted and running (busy-flag parity). */
    val historyImportInFlight: Flow<Boolean> = historyImport.inFlight

    /**
     * Durable "history raw import committed but its recompute unfinished" signal for the active
     * account, independent of the in-process Running/Completed state so Settings renders recovery
     * on cold launch. Gated on the one-time history flag: a bare backup RESTORE marker on a profile
     * that never imported history must not read as "historical minutes saved" (it needs only
     * derive). The account is read first, then the Room row — never inferred from scheduler state.
     */
    val historyImportRecoveryPending: Flow<Boolean> = profileDao.observe().map { profile ->
        val active = credentials.currentCredentials()?.steamId?.takeIf { it.isNotBlank() }
        profile != null && active != null &&
            profile.playtimeBackfilled && profile.pendingImportRecompute &&
            profile.pendingImportRecomputeSteamId == active
    }

    // ------------------------------------------------------------- LibraryBaselineGateway (UI facts)

    /** Whether the active account has a confirmed library baseline (Import affordance). */
    override suspend fun baselineConfirmed(): Boolean =
        libraryBaselineReadiness() == LibraryBaselineReadiness.Confirmed

    /** Whether the one-time history import has committed for [steamId] ("Already imported"). */
    override suspend fun importBackfilled(steamId: String): Boolean =
        profileDao.get()?.let { it.steamId == steamId && it.playtimeBackfilled } ?: false

    /** Whether a history raw import committed for [steamId] but its recompute is unfinished. */
    override suspend fun recomputePending(steamId: String): Boolean =
        profileDao.get()?.let {
            it.steamId == steamId && it.playtimeBackfilled && it.pendingImportRecompute &&
                it.pendingImportRecomputeSteamId == steamId
        } ?: false

    /**
     * One-shot read of the current aggregates. Callers comparing a *before* against a computed
     * *after* need a value, not a stream — sampling the flow would race the recompute they are
     * about to describe.
     */
    suspend fun currentStats(): PlayerStats? = profileDao.get()?.toDomain()

    /** Enqueue an immediate one-time poll. */
    fun syncNow() = syncScheduler.syncNow()

    /** Enqueue a one-time full achievement reconciliation pass, bypassing deferred constraints. */
    suspend fun reconcileNow() = syncScheduler.reconcileNow(force = true)

    /**
     * Project one admitted library-poll operation onto its attributable outcome, from the durable
     * evidence that operation recorded (or from its absence). Attribution is exact: only evidence
     * carrying the same work identity **and** the same account as [request] may classify it, and
     * scheduler success alone is never consulted — a finished job without committed evidence is
     * [LibraryPollResult.Unknown], never a fabricated [LibraryPollResult.Committed].
     */
    suspend fun libraryPollResult(request: LibraryPollRequest): LibraryPollResult {
        val row = evidenceDao.getFor(request.workIdentity, request.accountSteamId)
        return pollMapper.resultFor(request, row?.toBoundaryEvidence())
    }

    /**
     * Whether the active account's owned-library baseline is durably confirmed. Confirmed requires
     * an active account with no pending reset and [LibraryBaselineProvenance.LOCAL] evidence for
     * exactly that account (including a confirmed empty library). A generic/restored `lastSyncAt`,
     * nonempty local rows, [playtimeBackfilled], scheduler success, a different account's
     * confirmation, or a later failed refresh cannot manufacture readiness.
     */
    suspend fun libraryBaselineReadiness(): LibraryBaselineReadiness {
        val active = credentials.currentCredentials()?.steamId?.takeIf { it.isNotBlank() }
            ?: return LibraryBaselineReadiness.Unknown
        return pollMapper.readinessFor(
            activeSteamId = active,
            pendingResetSteamId = accountChangeMarker.pendingSteamId(),
            evidence = profileDao.get()?.toBaselineEvidence(),
        )
    }

    /**
     * Submit an explicit history-import consent for the active account and run it to settlement
     * (stabilize-first-run-setup, task 5.1). Consent is persisted before the operation launches;
     * repeat invocations are idempotent no-ops reporting [HistoryImportResult.AlreadyImported],
     * while overlapping taps coalesce onto the running admission without creating new consent.
     * [HistoryImportResult] replaces the old Boolean so onboarding and Settings see the same
     * truthful state (NeedsBaseline / AlreadyImported / PendingRecompute / Failed / Superseded).
     *
     * @param expectedSteamId optional: the account the consent is recorded for (the parent records
     *   its IMPORT_REQUESTED phase against this exact account); default is the active account.
     * @param requestId optional opaque consent identity the parent generated for its phase record;
     *   the coordinator generates one when omitted.
     */
    suspend fun importSteamHistory(
        expectedSteamId: String? = null,
        requestId: String? = null,
    ): HistoryImportResult = historyImport.start(expectedSteamId, requestId)

    /**
     * Retry/resume a previously attempted import that did not settle, without creating new consent.
     * Returns null when there is nothing pending for the active account.
     */
    suspend fun resumeHistoryImport(): HistoryImportResult? = historyImport.resume()

    /** Startup recovery for a recorded consent that died before launch / raw commit. */
    suspend fun recoverPendingHistoryImport() = historyImport.recoverPending()

    /** The shared import state onboarding and Settings render (including in-flight). */
    val historyImportState: Flow<HistoryImportState> = historyImport.state

    /**
     * The durable recorded consent, for kill-between-consent-and-phase reconciliation: the parent
     * phase store records IMPORT_REQUESTED/request against [HistoryImportState.Running] and can
     * re-derive what was owed from this flow after a crash.
     */
    val historyImportPendingRequest: Flow<HistoryImportRequestRecord?> = historyImport.pendingRequest

    /** One-shot read of the currently recorded explicit request, or null. */
    suspend fun currentHistoryImportPendingRequest(): HistoryImportRequestRecord? =
        historyImport.currentPendingRequest()

    /**
     * Whether [requestId] for [accountSteamId] was invalidated by an explicit reset. A first-run
     * phase replay re-submitting a voided request id must never silently re-import — the phase
     * agent drops the stale IMPORT_REQUESTED pointer when this returns true.
     */
    suspend fun isHistoryImportRequestVoided(accountSteamId: String, requestId: String): Boolean =
        historyImport.isRequestVoided(accountSteamId, requestId)

    /**
     * Undo a prior history import: clears the frozen offsets and the flag, then recomputes so
     * the import can be offered again. Leaves tracked sessions and streaks intact; the applied
     * cloud imported-play transfer guard remains enforced.
     */
    suspend fun resetSteamHistoryImport(): PlaytimeBackfillResetResult =
        historyImport.resetSteamHistoryImport()
}

private fun PlayerProfile.toDomain() = PlayerStats(
    steamId = steamId,
    steamLevel = steamLevel,
    totalXp = totalXp,
    level = level,
    currentStreak = currentStreak,
    longestStreak = longestStreak,
    lastSyncAt = lastSyncAt,
    lastSyncError = lastSyncError,
    playtimeBackfilled = playtimeBackfilled,
    personaName = personaName,
    avatarUrl = avatarUrl,
    pendingHistoryRecompute = playtimeBackfilled && pendingImportRecompute,
    pendingHistoryRequestId = pendingImportRecomputeRequestId
        ?.takeIf { playtimeBackfilled && pendingImportRecompute },
)

private fun DailyProgress.toDomain() = DayProgress(
    date = date,
    minutesPlayed = minutesPlayed,
    goalMinutesPlayed = goalMinutesPlayed,
    questMet = questMet,
)

/**
 * Profile confirmation columns as repository-boundary baseline evidence. Backup restore and legacy
 * migration never write these columns, so any present pair was recorded by the accepted raw library
 * transaction of a poll for this account — [LibraryBaselineProvenance.LOCAL] — and may grant
 * readiness; absent columns (or a blank steam id) are not evidence.
 */
internal fun PlayerProfile.toBaselineEvidence(): LibraryBaselineEvidence? {
    val id = confirmedLibrarySteamId?.takeIf { it.isNotBlank() } ?: return null
    val at = confirmedLibraryAt ?: return null
    return LibraryBaselineEvidence(
        steamId = id,
        confirmedAt = at,
        provenance = LibraryBaselineProvenance.LOCAL,
    )
}

/**
 * Stored durable evidence as the repository-boundary evidence the pure mapper projects. A malformed
 * outcome or refusal parses to pending, so the projection is [LibraryPollResult.Unknown] rather
 * than a guessed classification.
 */
internal fun LibraryPollEvidenceRecord.toBoundaryEvidence(): LibraryPollEvidence = when (outcome) {
    LibraryPollOutcomeKind.COMMITTED.name ->
        LibraryPollEvidence.Committed(workIdentity, accountSteamId, gameCount, lastSyncAt)
    LibraryPollOutcomeKind.NOT_PERFORMED.name -> {
        val refusalName = refusal?.let { runCatching { LibraryPollRefusal.valueOf(it) }.getOrNull() }
            ?: return LibraryPollEvidence.Pending
        LibraryPollEvidence.NotPerformed(workIdentity, accountSteamId, refusalName)
    }
    LibraryPollOutcomeKind.FAILED.name ->
        LibraryPollEvidence.Failed(workIdentity, accountSteamId, reason ?: "Unknown sync failure")
    else -> LibraryPollEvidence.Pending
}
