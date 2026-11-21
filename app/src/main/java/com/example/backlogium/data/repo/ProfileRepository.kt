package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.domain.PlaytimeBackfillUseCase
import com.example.backlogium.domain.PlaytimeBackfillResetResult
import com.example.backlogium.work.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
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
    /** True once historical playtime is captured and its derived recompute has completed. */
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
    credentials: CredentialsRepository,
) : HistoryImportGateway {
    override val historyImportReadiness: Flow<HistoryImportReadiness> = combine(
        profileDao.observe(), credentials.credentialsStateFlow, ::historyImportReadinessFor,
    )
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
     * One-time opt-in import of historical Steam playtime into XP. Idempotent: a no-op once
     * already imported. Recompute inside the use-case updates the observed profile, so Home
     * reflects the new XP/level automatically.
     */
    override suspend fun importSteamHistory(): Boolean = playtimeBackfill()

    /**
     * Undo a prior history import: clears the frozen offsets and the flag, then recomputes so
     * the import can be offered again. Leaves tracked sessions and streaks intact.
     */
    suspend fun resetSteamHistoryImport(): PlaytimeBackfillResetResult = playtimeBackfill.reset()
}

internal fun historyImportReadinessFor(profile: PlayerProfile?, configured: CredentialsState): HistoryImportReadiness = when {
    configured !is CredentialsState.Configured || profile?.steamId != configured.steamId ->
        HistoryImportReadiness.NEEDS_SYNC
    profile.playtimeBackfilled && !profile.pendingImportRecompute -> HistoryImportReadiness.IMPORTED
    profile.lastSyncAt > 0L -> HistoryImportReadiness.READY
    else -> HistoryImportReadiness.NEEDS_SYNC
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
    playtimeBackfilled = playtimeBackfilled && !pendingImportRecompute,
    personaName = personaName,
    avatarUrl = avatarUrl,
)

private fun DailyProgress.toDomain() = DayProgress(
    date = date,
    minutesPlayed = minutesPlayed,
    goalMinutesPlayed = goalMinutesPlayed,
    questMet = questMet,
)
