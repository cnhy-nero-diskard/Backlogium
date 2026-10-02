package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.work.SteamSyncCoordinator
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Startup recovery for an unfinished raw-data commit whose follow-up gamification recompute never
 * ran (stabilize-first-run-setup, tasks 5.5/5.6).
 *
 * Two durable sources signal unfinished work:
 *
 * 1. **Room pending marker** ([PlayerProfile.pendingImportRecompute]): set atomically inside a raw
 *    transaction (a backup merge, or the tightened history import) and cleared only by a completed
 *    recompute. Its provenance columns ([PlayerProfile.pendingImportRecomputeSource], and the
 *    [PlayerProfile.pendingImportRecomputeSteamId] account it belongs to) let recovery present an
 *    explicit history import as [RecomputeSource.BACKFILL] while a backup merge stays
 *    [RecomputeSource.RESTORE] — neither is ever announced as earned progress.
 * 2. **DataStore request store**: an explicit history-import consent recorded before launch whose
 *    operation died before the raw commit. Recovery replays it through [HistoryImportCoordinator]
 *    (idempotent; the raw flag and marker make a post-commit replay a no-op).
 *
 * Resolution uses the same sync -> derived lock order as the import and account reset, and never
 * recomputes a pending marker that belongs to a previous account.
 */
class PendingImportRecomputeUseCase @Inject constructor(
    private val playerProfileDao: PlayerProfileDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val syncCoordinator: SteamSyncCoordinator,
    private val account: HistoryImportAccountGateway,
    private val coordinator: HistoryImportCoordinator,
) {
    suspend operator fun invoke() {
        if (playerProfileDao.get()?.pendingImportRecompute == true) {
            resolvePendingRecompute()
        }
        // Always reconcile the recorded consent too: an import the marker path just settled is
        // consumed exactly once (AlreadyImported -> clear), an unfinished one simply resumes
        // (idempotent), and a consent that never launched is replayed — never duplicated.
        coordinator.recoverPending()
    }

    private suspend fun resolvePendingRecompute() {
        syncCoordinator.withLock {
            derivedStateWrites.withLock {
                val profile = playerProfileDao.get() ?: return@withLock
                // Re-check with the locks held: another writer may have already resolved this.
                if (!profile.pendingImportRecompute) return@withLock

                // A pending recompute left by a previous account must not run over the replacement
                // account; the account reset clears it, and until then it stays silent.
                val markerSteamId = profile.pendingImportRecomputeSteamId
                val active = account.activeSteamId()
                if (markerSteamId != null && markerSteamId != active) return@withLock
                if (active == null || account.pendingResetSteamId() != null) return@withLock

                // Provenance decides the presentation baseline. Legacy markers (null provenance)
                // predate this change and can only have come from a backup merge -> RESTORE.
                val source =
                    if (profile.pendingImportRecomputeSource == RecomputeSource.BACKFILL.name) {
                        RecomputeSource.BACKFILL
                    } else {
                        RecomputeSource.RESTORE
                    }
                val rules = settings.ruleConfigWithVersionFlow.first()
                gamificationUpdater.recompute(
                    today = time.today(),
                    source = source,
                    config = rules.config,
                    configVersion = rules.version,
                )
            }
        }
    }
}