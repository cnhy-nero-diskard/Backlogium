package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Runs one Steam poll: fetch -> diff into sessions -> persist -> recompute gamification.
 * Fully self-contained and idempotent enough to run on WorkManager's periodic schedule or
 * as an expedited "Sync now". Never discards last-good data on failure.
 */
@HiltWorker
class SteamSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val steamApi: SteamApi,
    private val settings: SettingsDataStore,
    private val credentials: CredentialsRepository,
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val profileDao: PlayerProfileDao,
    private val differ: SessionDiffer,
    private val gamificationUpdater: GamificationUpdater,
    private val achievementRepository: AchievementRepository,
    private val time: TimeProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val creds = credentials.currentCredentials()
        if (creds == null) {
            recordError("Steam not configured")
            return Result.success() // config issue: retrying won't help
        }
        val apiKey = creds.apiKey
        val steamId = creds.steamId

        return try {
            val owned = steamApi.getOwnedGames(apiKey, steamId)
            val games = owned.response.games

            if (games.isEmpty()) {
                // Empty response usually means a private profile. Keep last-good data.
                recordError("No games returned — your Steam profile may be private")
                return Result.success()
            }

            val steamLevel = runCatching {
                steamApi.getSteamLevel(apiKey, steamId).response.playerLevel
            }.getOrDefault(profileDao.get()?.steamLevel ?: 0)

            persistPoll(games, apiKey, steamId, steamLevel)
            Result.success()
        } catch (e: Exception) {
            // Network / transient error: surface it, keep data, let WorkManager back off.
            recordError(e.message ?: "Sync failed")
            Result.retry()
        }
    }

    private suspend fun persistPoll(
        games: List<com.example.backlogium.data.remote.dto.OwnedGameDto>,
        apiKey: String,
        steamId: String,
        steamLevel: Int,
    ) {
        val now = time.nowMillis()
        val today = time.today()
        val config = settings.ruleConfigFlow.first()

        val profile = profileDao.get() ?: PlayerProfile(steamId = steamId)
        val isBaseline = profile.lastSyncAt == 0L
        val previousPollAt = if (isBaseline) now else profile.lastSyncAt

        // Reconstruct prior diff state from Room BEFORE writing new playtime.
        val existingGames = gameDao.getAll().associateBy { it.appId }
        val priorStates = existingGames.mapValues { (appId, game) ->
            val open = sessionDao.getOpenSession(appId)
            SessionDiffer.GameDiffState(
                lastPlaytime = game.lastPlaytime,
                openSession = open?.let {
                    SessionDiffer.OpenSession(
                        startAt = it.startAt,
                        minutes = it.minutes,
                        lastIncreaseAt = it.endAt ?: it.startAt,
                    )
                },
            )
        }

        val polls = games.map { SessionDiffer.PollGame(it.appid, it.playtimeForever) }
        val diff = if (isBaseline) {
            differ.baseline(polls)
        } else {
            differ.diff(polls, priorStates, now = now, previousPollAt = previousPollAt)
        }

        applySessionActions(diff.actions)

        // Upsert games with fresh remote fields + new baseline, preserving goal tagging.
        val updatedGames = games.map { dto ->
            val existing = existingGames[dto.appid]
            Game(
                appId = dto.appid,
                name = dto.name,
                iconUrl = SteamIconMapper.iconUrl(dto.appid, dto.imgIconUrl),
                playtimeForever = dto.playtimeForever,
                playtime2Weeks = dto.playtime2Weeks,
                lastPlaytime = diff.newLastPlaytime[dto.appid] ?: dto.playtimeForever,
                isGoal = existing?.isGoal ?: false,
                targetMinutes = existing?.targetMinutes,
                lastSyncedAt = now,
                // Preserve the frozen opt-in history offset; rebuilding the row from the DTO
                // would otherwise reset it to 0 and wipe imported XP on the next sync.
                backfillMinutes = existing?.backfillMinutes ?: 0,
            )
        }
        gameDao.upsertAll(updatedGames)

        // Attribute this poll's deltas to today's local date; always ensure today's row
        // exists so the quest/streak evaluation sees the current day.
        val goalIds = updatedGames.filter { it.isGoal }.map { it.appId }.toSet()
        val addedAny = diff.playedDeltaByAppId.values.sum()
        val addedGoal = diff.playedDeltaByAppId.filterKeys { it in goalIds }.values.sum()
        val todayKey = today.toString()
        val existingDay = dailyProgressDao.getByDate(todayKey) ?: DailyProgress(todayKey)
        dailyProgressDao.upsert(
            existingDay.copy(
                minutesPlayed = existingDay.minutesPlayed + addedAny,
                goalMinutesPlayed = existingDay.goalMinutesPlayed + addedGoal,
            ),
        )

        // Update sync status, then fetch in-scope achievements (freshness-gated, best-effort —
        // never fails the poll) before recomputing derived gamification values.
        profileDao.upsert(
            profile.copy(
                steamId = steamId,
                steamLevel = steamLevel,
                lastSyncAt = now,
                lastSyncError = null,
            ),
        )
        runCatching { achievementRepository.syncInScopeGames(apiKey, steamId) }
        gamificationUpdater.recompute(today, config)
    }

    private suspend fun applySessionActions(actions: List<SessionDiffer.SessionAction>) {
        for (action in actions) {
            when (action) {
                is SessionDiffer.SessionAction.Open -> sessionDao.insert(
                    Session(
                        appId = action.appId,
                        startAt = action.startAt,
                        endAt = action.endAt,
                        minutes = action.minutes,
                        open = true,
                    ),
                )

                is SessionDiffer.SessionAction.Extend ->
                    sessionDao.getOpenSession(action.appId)?.let {
                        sessionDao.update(it.copy(minutes = action.minutes, endAt = action.endAt))
                    }

                is SessionDiffer.SessionAction.Close ->
                    sessionDao.getOpenSession(action.appId)?.let {
                        sessionDao.update(it.copy(open = false, endAt = action.endAt))
                    }
            }
        }
    }

    private suspend fun recordError(message: String) {
        val profile = profileDao.get() ?: PlayerProfile()
        profileDao.upsert(profile.copy(lastSyncError = message))
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "steam_sync_periodic"
        const val ONE_TIME_NAME = "steam_sync_now"
    }
}
