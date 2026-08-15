package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.PlayerProfileDao
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Resolves a backup merge whose raw-data transaction committed but whose follow-up gamification
 * recompute never ran — the process ended in the window between the two, which
 * [PlayerProfile.pendingImportRecompute][com.example.backlogium.data.local.entity.PlayerProfile.pendingImportRecompute]
 * exists to detect (auditfix-backup-integrity design.md decision 2). That flag lives in Room so it
 * commits atomically with the merge; this recovery step runs the same recompute
 * [BackupMergeEngine][com.example.backlogium.data.backup.BackupMergeEngine] would have run, and the
 * recompute's own [PlayerProfileDao.updateGamification] clears the flag on success.
 *
 * Mirrors [DailyProgressBackfillUseCase]'s startup-recovery shape, but the guard here is a Room
 * column rather than a DataStore flag, because it must commit as part of the same transaction as
 * the data it describes.
 */
class PendingImportRecomputeUseCase @Inject constructor(
    private val playerProfileDao: PlayerProfileDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
) {
    suspend operator fun invoke() {
        if (playerProfileDao.get()?.pendingImportRecompute != true) return
        derivedStateWrites.withLock {
            // Re-check with the lock held: another writer may have already resolved this since
            // the unguarded check above.
            if (playerProfileDao.get()?.pendingImportRecompute != true) return@withLock
            val rules = settings.ruleConfigWithVersionFlow.first()
            gamificationUpdater.recompute(
                today = time.today(),
                source = RecomputeSource.RESTORE,
                config = rules.config,
                configVersion = rules.version,
            )
        }
    }
}
