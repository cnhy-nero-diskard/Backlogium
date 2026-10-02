package com.example.backlogium.domain

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.history.HistoryImportRequestRecord
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.work.SteamSyncCoordinator
import javax.inject.Inject
import kotlinx.coroutines.flow.first

enum class PlaytimeBackfillResetResult {
    RESET,
    /**
     * The reset's raw unit committed (every offset cleared, the flag unset, atomically) but its
     * administrative recomputation did not finalize. The consent is already void; a later recovery
     * recomputes from tracked-only under the pending marker. Never re-imports.
     */
    RESET_PENDING_RECOMPUTE,
    BLOCKED_BY_CLOUD_TRANSFER,
    /**
     * A durable account change is in flight; the reset did not touch any state and the recorded
     * consent (if any) is untouched. Retried after the change completes.
     */
    BLOCKED_BY_ACCOUNT_CHANGE,
    NO_OP,
}

/**
 * One-time, opt-in import of historical Steam playtime into XP (add-playtime-backfill), tightened
 * and hardened for first-run setup (stabilize-first-run-setup, tasks 5.1-5.7).
 *
 * The one-time freeze is `backfillMinutes = max(0, playtimeForever − trackedMinutes(appId))` per
 * Steam-owned game, captured in one Room transaction with the profile flag and the pending-BACKFILL
 * recomputation marker, so a concurrent sync commit can never advance one part of the snapshot
 * while the other is read ("atomic lifetime + tracked snapshot offsets"). The engine is unchanged:
 * [GamificationUpdater.recompute] feeds `backfillMinutes + trackedMinutes` as one cumulative total,
 * which the existing per-game taper bounds naturally.
 *
 * Shared-to-owned converted credits are preserved rather than overwritten: a Steam-owned row whose
 * [com.example.backlogium.data.local.entity.Game.backfillMinutes] is already nonzero carries a
 * folded manual estimate (`GameDao.convertSharedToOwned`) and is deliberately not re-frozen, so the
 * tightening never erases or double-counts an existing credit. Family-shared rows, their
 * [com.example.backlogium.data.local.entity.Game.manualSharedMinutes], and the hidden-game set are
 * never touched. No dated session, daily quest credit, or streak is ever synthesized from lifetime
 * counters — the import writes offsets and a flag only.
 *
 * The operation is account-scoped and idempotent. Its commit boundary is guarded by the same
 * sync -> derived -> Room lock order as account reset and cloud re-filing, revalidates the
 * expected account, the account-change admission marker, the one-time flag, and the confirmed
 * baseline inside that barrier, and reports [HistoryImportResult] rather than a Boolean.
 */
class PlaytimeBackfillUseCase @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val playerProfileDao: PlayerProfileDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
    private val syncCoordinator: SteamSyncCoordinator = SteamSyncCoordinator(),
    private val account: HistoryImportAccountGateway = UnavailableHistoryImportAccountGateway,
) {

    /**
     * Run the shared one-time import for an explicitly consented [request].
     *
     * The account and the account-change admission marker are revalidated under [syncCoordinator]
     * before any Room state is touched; eligibility re-validation (one-time flag and confirmed
     * baseline) happens under the derived-state lock, so no writer can interleave with the raw
     * snapshot. A raw-committed import whose recomputation is pending is resumed — never refrozen
     * from a growing Steam lifetime total.
     */
    suspend fun importSteamHistory(request: HistoryImportRequestRecord): HistoryImportResult =
        syncCoordinator.withLock {
            // The durable account-change marker is the admission barrier: while a reset waits on
            // this same process lock, an old account's request must not commit for the replacement
            // account, and the marker read is coherent with every reset because they serialize here.
            if (account.pendingResetSteamId() != null) {
                return@withLock HistoryImportResult.Superseded
            }
            val activeSteamId = account.activeSteamId()
            if (activeSteamId == null || activeSteamId != request.steamId) {
                return@withLock HistoryImportResult.Superseded
            }
            derivedStateWrites.withLock {
                importLocked(request)
            }
        }

    /**
     * The commit boundary, holding the derived-state coordinator.
     *
     * No network work happens under either lock: the whole raw snapshot commits as one Room unit
     * (offsets + flag + pending-BACKFILL marker), and only then does the existing administrative
     * recomputation protocol run to clear the marker.
     *
     * The expected account and the durable account-change marker are revalidated AGAIN here, at
     * the derived boundary and immediately before the raw commit: the marker is recorded by the
     * account-change protocol *outside* its process lock, so it can flip into place while this
     * import waited for the derived mutex. The re-check is a DataStore read made before the Room
     * transaction opens — never inside it.
     */
    private suspend fun importLocked(request: HistoryImportRequestRecord): HistoryImportResult {
        if (account.pendingResetSteamId() != null) {
            return HistoryImportResult.Superseded
        }
        val activeSteamId = account.activeSteamId()
        if (activeSteamId == null || activeSteamId != request.steamId) {
            return HistoryImportResult.Superseded
        }
        val stored = playerProfileDao.get()
        // The stored library belonging to another account means this request cannot act here.
        if (stored != null && stored.steamId.isNotBlank() && stored.steamId != request.steamId) {
            return HistoryImportResult.Superseded
        }

        // A completed import is a historical fact that outlives baseline confirmation: a legacy
        // install that imported before baseline evidence existed must stay completed, never regress
        // to NeedsBaseline.
        if (stored?.playtimeBackfilled == true) {
            return if (stored.pendingImportRecompute) {
                resumeCommittedImport(request, stored)
            } else {
                HistoryImportResult.AlreadyImported
            }
        }

        // Baseline eligibility is checked only for a genuine one-time import, after the
        // already-imported branch above. Confirmation is explicit same-account evidence only; a
        // generic/restored timestamp, nonempty rows, or credentials are not readiness.
        val confirmed = stored?.confirmedLibrarySteamId
        if (confirmed != request.steamId || stored?.confirmedLibraryAt == null) {
            return HistoryImportResult.NeedsBaseline
        }

        val outcome = try {
            transaction.run { commitSnapshot(request) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // The atomic raw unit wrote nothing (a real Room transaction rolls back); report a
            // retryable failure rather than leaking an exception into the shared coordinator.
            return HistoryImportResult.Failed(t.message ?: "History import failed")
        }
        return when (outcome) {
            is SnapshotOutcome.AlreadyImported -> HistoryImportResult.AlreadyImported
            is SnapshotOutcome.NeedsBaseline -> HistoryImportResult.NeedsBaseline
            is SnapshotOutcome.Committed ->
                if (runBackfillRecompute()) {
                    HistoryImportResult.Imported(outcome.snapshot.creditedGames, outcome.snapshot.creditedMinutes)
                } else {
                    HistoryImportResult.PendingRecompute
                }
        }
    }

    /**
     * Resume a raw-committed import whose recomputation is pending, when only the Room marker
     * remains (the explicit request itself has already been consumed). Never recaptures offsets.
     *
     * @return null when the active account has nothing pending to resume.
     */
    suspend fun resumePendingImport(expectedSteamId: String): HistoryImportResult? =
        syncCoordinator.withLock {
            if (account.pendingResetSteamId() != null) {
                return@withLock HistoryImportResult.Superseded
            }
            val activeSteamId = account.activeSteamId()
            if (activeSteamId == null || activeSteamId != expectedSteamId) {
                return@withLock HistoryImportResult.Superseded
            }
            derivedStateWrites.withLock {
                val stored = playerProfileDao.get()
                if (stored?.playtimeBackfilled != true || !stored.pendingImportRecompute) {
                    return@withLock null
                }
                resumeCommittedImport(
                    request = HistoryImportRequestRecord(
                        steamId = expectedSteamId,
                        requestedAt = 0L,
                        requestId = stored.pendingImportRecomputeRequestId
                            ?.takeIf { it.isNotEmpty() }
                            ?: "resume",
                    ),
                    stored = stored,
                )
            }
        }

    /**
     * Resume an import whose raw transaction committed but whose administrative recomputation did
     * not finalize. The committed offsets are read only to report; nothing is ever recaptured from
     * Steam's growing totals. The account-admission marker is revalidated here too, at the derived
     * boundary, so a marker recorded while this resume waited cannot be resolved over the
     * replacement account.
     */
    private suspend fun resumeCommittedImport(
        request: HistoryImportRequestRecord,
        stored: PlayerProfile,
    ): HistoryImportResult {
        if (account.pendingResetSteamId() != null) {
            return HistoryImportResult.Superseded
        }
        val activeSteamId = account.activeSteamId()
        if (activeSteamId == null || activeSteamId != request.steamId) {
            return HistoryImportResult.Superseded
        }
        val markerSteamId = stored.pendingImportRecomputeSteamId
        if (markerSteamId != null && markerSteamId != request.steamId) {
            return HistoryImportResult.Superseded
        }
        val credited = committedCredits()
        return if (runBackfillRecompute()) {
            HistoryImportResult.Imported(credited.creditedGames, credited.creditedMinutes)
        } else {
            HistoryImportResult.PendingRecompute
        }
    }

    /** What one raw snapshot commit produced, including the transaction-coherent early no-ops. */
    private sealed interface SnapshotOutcome {
        /** A racing writer consumed the one-time import before this transaction opened. */
        data object AlreadyImported : SnapshotOutcome

        /** The baseline no longer matches the request (e.g. reset raced in); nothing was written. */
        data object NeedsBaseline : SnapshotOutcome

        data class Committed(val snapshot: BackfillSnapshot) : SnapshotOutcome
    }

    /** The raw snapshot, as one Room unit: offsets, the one-time flag, and the pending marker. */
    private suspend fun commitSnapshot(request: HistoryImportRequestRecord): SnapshotOutcome {
        // Transaction-coherent baseline/flag re-read: under the derived mutex no other raw writer
        // can interleave, and a reset's Room clear is likewise serialized — this second net only
        // answers the account-change marker race defensively and costs one coherent Room read.
        val fresh = playerProfileDao.get()
        if (fresh?.playtimeBackfilled == true) {
            return SnapshotOutcome.AlreadyImported
        }
        if (fresh?.confirmedLibraryAt == null || fresh.confirmedLibrarySteamId != request.steamId) {
            return SnapshotOutcome.NeedsBaseline
        }
        val games = gameDao.getAll()
        val trackedByGame = sessionDao.trackedMinutesByGame().associate { it.appId to it.minutes }
        val offsetsByAppId = games
            .asSequence()
            // Only Steam-owned rows participate. For each row the frozen offset is the coherent
            // max of an existing frozen value (a shared->owned conversion credit folded in by
            // GameDao.convertSharedToOwned) and the lifetime-minus-tracked snapshot: the
            // tightening never erases an existing credit, and never loses freshly eligible
            // historical minutes the way skipping the whole game would. A converted credit
            // falls back from the snapshot only when the snapshot is larger — each minute is
            // counted once, never twice. Family-shared rows are never touched.
            .filter { it.source == GameSource.STEAM_OWNED }
            .associate { game ->
                val tracked = trackedByGame[game.appId] ?: 0
                val computed = (game.playtimeForever - tracked).coerceAtLeast(0)
                game.appId to maxOf(game.backfillMinutes, computed)
            }
        gameDao.applyBackfill(offsetsByAppId)
        playerProfileDao.updatePlaytimeBackfilled(true)
        playerProfileDao.markPendingImportRecompute(
            source = RecomputeSource.BACKFILL.name,
            steamId = request.steamId,
            requestId = request.requestId,
        )
        return SnapshotOutcome.Committed(
            BackfillSnapshot(
                creditedGames = offsetsByAppId.values.count { it > 0 },
                creditedMinutes = offsetsByAppId.values.sumOf { it.toLong() },
            ),
        )
    }

    /** The committed frozen offsets, for reporting only (never recaptured/re-frozen here). */
    private suspend fun committedCredits(): BackfillSnapshot {
        val offsets = gameDao.getAll()
            .asSequence()
            .filter { it.source == GameSource.STEAM_OWNED }
            .map { it.backfillMinutes.toLong() }
            .toList()
        return BackfillSnapshot(
            creditedGames = offsets.count { it > 0 },
            creditedMinutes = offsets.sum(),
        )
    }

    /**
     * Run the existing administrative recomputation protocol. Only its successful finalize clears
     * the pending marker ([PlayerProfileDao.updateGamification] clears it); a failure here leaves
     * the marker for recovery and the result is [HistoryImportResult.PendingRecompute].
     */
    private suspend fun runBackfillRecompute(): Boolean = try {
        recompute()
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    /**
     * Undo the import: clear every frozen offset, unset the flag, and recompute so XP falls back to
     * tracked-only. After this the import can be offered — and run — again, which refreezes the
     * same historical portion. Tracked sessions, daily progress, and streaks are untouched. The
     * applied cloud imported-play transfer guard is preserved: a reversal-before-reset remains
     * required.
     *
     * The raw unit (offsets + flag) is one atomic Room transaction together with a pending admin
     * marker, so a crash after the raw commit but before the recompute leaves recovery
     * ([PendingImportRecomputeUseCase]) in charge of the tracked-only values rather than a stale
     * aggregate. If that recompute does not finalize here, the result is
     * [PlaytimeBackfillResetResult.RESET_PENDING_RECOMPUTE] — the reset already committed and the
     * recorded consent stays void, so nothing can re-import a reset library.
     * @param expectedSteamId when provided, the account the reset was requested for; revalidated at
     *   the derived boundary so a queued old-account reset that survives an account change can never
     *   reset the replacement account's imports.
     */
    suspend fun reset(expectedSteamId: String? = null): PlaytimeBackfillResetResult = syncCoordinator.withLock {
        // The account-change admission barrier: while a reset waits on this same process lock, the
        // reset must not read or modify the old account's profile — the durable marker's reset
        // owns the transition. The marker read is coherent with every reset because they serialize.
        if (account.pendingResetSteamId() != null) {
            return@withLock PlaytimeBackfillResetResult.BLOCKED_BY_ACCOUNT_CHANGE
        }

        val refilingApplied = settings.cloudPresenceRefilingAppliedFlow.first()
        val receipt = settings.cloudPresenceRefilingReceiptFlow.first()
        if (refilingApplied && receipt?.transferredMinutesByAppId?.any { it.minutes > 0 } == true) {
            return@withLock PlaytimeBackfillResetResult.BLOCKED_BY_CLOUD_TRANSFER
        }

        derivedStateWrites.withLock {
            // Revalidate AGAIN immediately before the raw commit: the durable account-change marker
            // is recorded outside the process lock, so it can flip into place while this reset
            // waited for the derived mutex, and a reset requested for a previous account must not
            // act on the replacement account once the change completes.
            if (account.pendingResetSteamId() != null) {
                return@withLock PlaytimeBackfillResetResult.BLOCKED_BY_ACCOUNT_CHANGE
            }
            if (expectedSteamId != null) {
                val active = account.activeSteamId()?.trim()?.takeIf { it.isNotEmpty() }
                if (active != expectedSteamId) {
                    return@withLock PlaytimeBackfillResetResult.BLOCKED_BY_ACCOUNT_CHANGE
                }
            }
            val profile = playerProfileDao.get()
            if (profile == null) return@withLock PlaytimeBackfillResetResult.NO_OP

            val resetSteamId = account.activeSteamId()?.trim()?.takeIf { it.isNotEmpty() }
            transaction.run {
                gameDao.applyBackfill(gameDao.getAll().associate { it.appId to 0 })
                playerProfileDao.updatePlaytimeBackfilled(false)
                playerProfileDao.markPendingImportRecompute(
                    source = RecomputeSource.BACKFILL.name,
                    steamId = resetSteamId,
                    requestId = null,
                )
            }
            val recomputed = try {
                recompute()
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (recomputed) {
                PlaytimeBackfillResetResult.RESET
            } else {
                PlaytimeBackfillResetResult.RESET_PENDING_RECOMPUTE
            }
        }
    }

    private suspend fun recompute() {
        val rules = settings.ruleConfigWithVersionFlow.first()
        gamificationUpdater.recompute(
            today = time.today(),
            source = RecomputeSource.BACKFILL,
            config = rules.config,
            configVersion = rules.version,
        )
    }
}

/** What one raw import snapshot committed, for the [HistoryImportResult.Imported] report. */
private data class BackfillSnapshot(
    val creditedGames: Int,
    val creditedMinutes: Long,
)
