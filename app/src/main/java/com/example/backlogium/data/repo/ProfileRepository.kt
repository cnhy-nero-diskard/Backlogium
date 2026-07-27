package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.domain.PlaytimeBackfillUseCase
import com.example.backlogium.work.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The engine's persisted outputs plus sync status, as consumers see them. */
data class PlayerStats(
    val steamId: String,
    val steamLevel: Int,
    val totalXp: Int,
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
    private val playtimeBackfill: PlaytimeBackfillUseCase,
) {
    val profile: Flow<PlayerStats?> = profileDao.observe().map { it?.toDomain() }
    val dailyProgress: Flow<List<DayProgress>> = dailyProgressDao.observeAll()
        .map { rows -> rows.map(DailyProgress::toDomain) }

    /**
     * True while any Steam poll — scheduled or manual — is in flight (WorkManager-backed), held
     * briefly past completion so a fast sync stays perceptible.
     */
    val syncInProgress: Flow<Boolean> = syncScheduler.syncInProgress

    /**
     * One-shot read of the current aggregates. Callers comparing a *before* against a computed
     * *after* need a value, not a stream — sampling the flow would race the recompute they are
     * about to describe.
     */
    suspend fun currentStats(): PlayerStats? = profileDao.get()?.toDomain()

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
)

private fun DailyProgress.toDomain() = DayProgress(
    date = date,
    minutesPlayed = minutesPlayed,
    goalMinutesPlayed = goalMinutesPlayed,
    questMet = questMet,
)
