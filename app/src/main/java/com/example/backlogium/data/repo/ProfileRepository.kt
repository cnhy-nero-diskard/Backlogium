package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.domain.PlaytimeBackfillUseCase
import com.example.backlogium.work.SyncScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Profile aggregates, per-day stats, the manual "Sync now" trigger, and history import. */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: PlayerProfileDao,
    private val dailyProgressDao: DailyProgressDao,
    private val syncScheduler: SyncScheduler,
    private val playtimeBackfill: PlaytimeBackfillUseCase,
) {
    val profile: Flow<PlayerProfile?> = profileDao.observe()
    val dailyProgress: Flow<List<DailyProgress>> = dailyProgressDao.observeAll()

    /** True while a manual "Sync now" poll is enqueued or running (WorkManager-backed). */
    val syncInProgress: Flow<Boolean> = syncScheduler.syncInProgress

    /** Enqueue an immediate one-time poll. */
    fun syncNow() = syncScheduler.syncNow()

    /**
     * One-time opt-in import of historical Steam playtime into XP. Idempotent: a no-op once
     * already imported. Recompute inside the use-case updates the observed profile, so Home
     * reflects the new XP/level automatically.
     */
    suspend fun importSteamHistory(): Boolean = playtimeBackfill()

    /**
     * Undo a prior history import: clears the frozen offsets and the flag, then recomputes so
     * the import can be offered again. Leaves tracked sessions and streaks intact.
     */
    suspend fun resetSteamHistoryImport() = playtimeBackfill.reset()
}
