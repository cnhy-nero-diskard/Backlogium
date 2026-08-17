package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.backup.BackupRepository
import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.AchievementLibraryFetch
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.PlayerIdentity
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.mergePlayerIdentity
import com.example.backlogium.domain.persistVersionChecked
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Minutes credited to one local calendar date by a sync poll. */
internal data class DailyProgressCredit(
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
)

/**
 * Keep the owned-games poll independent from the optional fine-grained presence start. A worker
 * can discover a game while backgrounded, where the platform may reject the service request; the
 * Steam data fetch must still be allowed to complete and determine the worker's own result.
 */
internal suspend fun <T> fetchOwnedGamesAfterPresenceStart(
    gameDetected: Boolean,
    startPresence: suspend () -> Unit,
    fetchOwnedGames: suspend () -> T,
): kotlin.Result<T> {
    if (gameDetected) {
        try {
            startPresence()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Presence is best-effort; the owned-games poll is the sync's source of truth.
        }
    }

    return try {
        kotlin.Result.success(fetchOwnedGames())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        kotlin.Result.failure(error)
    }
}

/**
 * Attribute only newly observed session minutes to each session's start date. A session remains
 * atomic across midnight; [SessionDiffer.SessionAction.addedMinutes] is the delta for an Extend,
 * not the session's accumulated total.
 */
internal fun attributeDailyProgress(
    actions: List<SessionDiffer.SessionAction>,
    goalAppIds: Set<Long>,
    zone: ZoneId,
): Map<String, DailyProgressCredit> = actions
    .asSequence()
    .filter { it.addedMinutes > 0 }
    .groupBy { action ->
        Instant.ofEpochMilli(action.startAt).atZone(zone).toLocalDate().toString()
    }
    .mapValues { (_, dayActions) ->
        DailyProgressCredit(
            minutesPlayed = dayActions.sumOf { it.addedMinutes },
            goalMinutesPlayed = dayActions
                .filter { it.appId in goalAppIds }
                .sumOf { it.addedMinutes },
        )
    }

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
    private val database: BacklogiumDatabase,
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val profileDao: PlayerProfileDao,
    private val differ: SessionDiffer,
    private val gamificationUpdater: GamificationUpdater,
    private val achievementRepository: AchievementRepository,
    private val backupRepository: BackupRepository,
    private val genreEnrichmentScheduler: GenreEnrichmentScheduler,
    private val presenceServiceStarter: PresenceServiceStarter,
    private val diagnostics: SyncRunRecorder,
    private val time: TimeProvider,
    private val syncCoordinator: SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        syncCoordinator.withLock { doWorkLocked() }

    private suspend fun doWorkLocked(): Result {
        val scope = diagnostics.begin(if (runAttemptCount > 0) "retry" else "scheduled")
        var outcome = SyncOutcome.FAILED
        var error: String? = null
        var examined = 0
        var updated = 0
        return try {
            val creds = credentials.currentCredentials()
            if (creds == null) {
                recordError("Steam not configured")
                outcome = SyncOutcome.SKIPPED_NO_CREDENTIALS
                return Result.success()
            }
            val apiKey = creds.apiKey
            val steamId = creds.steamId
            // Presence first, before any library-scale work. This one request carries both the
            // running game and the profile header identity; best-effort, so a failure yields null
            // and mergePlayerIdentity below keeps the stored values.
            val summary = runCatching {
                steamApi.getPlayerSummaries(apiKey, steamId, scope = scope).response.players.firstOrNull()
            }.getOrNull()

            // The detection path for a game that started while the app was never opened: ask the
            // starter, which records a foreground-required result instead of issuing an illegal
            // background service start. Deliberately ahead of getOwnedGames — presence doesn't
            // depend on the owned-games list, so neither a private library nor a failure later in
            // this run may cost the player detection.
            val owned = fetchOwnedGamesAfterPresenceStart(
                gameDetected = !summary?.gameId.isNullOrBlank(),
                startPresence = {
                    presenceServiceStarter.start(trigger = "sync")
                    Unit
                },
                fetchOwnedGames = {
                    steamApi.getOwnedGames(apiKey, steamId, scope = scope)
                },
            ).getOrThrow()
            val games = owned.response.games

            if (games.isEmpty()) {
                // Empty response usually means a private profile. Keep last-good data.
                recordError("No games returned — your Steam profile may be private")
                outcome = SyncOutcome.SKIPPED_EMPTY_OWNED_GAMES
                return Result.success()
            }

            val steamLevel = runCatching {
                steamApi.getSteamLevel(apiKey, steamId, scope).response.playerLevel
            }.getOrDefault(profileDao.get()?.steamLevel ?: 0)

            persistPoll(games, apiKey, steamId, steamLevel, summary, scope)
            examined = games.size
            updated = games.size
            outcome = SyncOutcome.SUCCESS
            Result.success()
        } catch (e: CancellationException) {
            outcome = SyncOutcome.INCOMPLETE
            throw e
        } catch (e: Exception) {
            // Network / transient error: surface it, keep data, let WorkManager back off.
            recordError(e.message ?: "Sync failed")
            error = e.message ?: "Sync failed"
            Result.retry()
        } finally {
            // NonCancellable: once cancelled, a plain suspend call here would throw at its first
            // suspension point and never persist the record — the exact case the INCOMPLETE
            // outcome above exists to make visible.
            withContext(NonCancellable) {
                runCatching { diagnostics.finish(scope, outcome, error, examined, updated) }
            }
        }
    }

    private suspend fun persistPoll(
        games: List<com.example.backlogium.data.remote.dto.OwnedGameDto>,
        apiKey: String,
        steamId: String,
        steamLevel: Int,
        summary: com.example.backlogium.data.remote.dto.PlayerSummaryDto?,
        scope: SyncRunRecorder.RunScope,
    ) {
        val now = time.nowMillis()
        val today = time.today()
        val polls = games.map { SessionDiffer.PollGame(it.appid, it.playtimeForever) }
        val configAtCompute = settings.ruleConfigWithVersionFlow.first()
        val provisionalDiff = readAndComputeDiff(polls, now)

        // Achievement requests are part of fetch, never the Room commit. Their payload is merged
        // below only after the raw playtime transaction has acquired its database boundary.
        val achievementFetch = achievementRepository.fetchLibraryGames(
            apiKey = apiKey,
            steamId = steamId,
            ownedGames = games.map {
                com.example.backlogium.data.achievement.AchievementFreshness.OwnedGame(
                    appId = it.appid,
                    playtimeForever = it.playtimeForever.toLong(),
                    playtime2Weeks = it.playtime2Weeks.toLong(),
                )
            },
            playtimeDeltaByAppId = provisionalDiff.playedDeltaByAppId,
            scope = scope,
        )
        scope.recordTiers(
            hot = achievementFetch.selection.hot.size,
            warm = achievementFetch.selection.warm.size,
            cold = achievementFetch.selection.cold.size,
            never = achievementFetch.selection.never.size,
        )

        withContext(NonCancellable) {
            database.withTransaction {
                commitRawPoll(
                    games = games,
                    steamId = steamId,
                    steamLevel = steamLevel,
                    summary = summary,
                    now = now,
                    polls = polls,
                    achievementFetch = achievementFetch,
                )
            }
        }

        // Store metadata is a separately scheduled best-effort concern: never await it or make
        // an otherwise-valid owned-games poll fail because the public Store is unavailable.
        runCatching { genreEnrichmentScheduler.ensureEnqueued() }

        // Raw data is durable now. Derived values deliberately follow through the existing
        // cross-store write-ahead protocol, after a version check against the configuration read
        // before compute. If rules moved, the stale candidate is refused and recomputed under the
        // current version instead of being silently stamped as current.
        persistDerived(today, configAtCompute)
        // Best-effort: a snapshot-write failure must never fail an otherwise-successful poll.
        runCatching { backupRepository.writeAutoSnapshotIfDue() }
    }

    private suspend fun readAndComputeDiff(
        polls: List<SessionDiffer.PollGame>,
        now: Long,
    ): SessionDiffer.DiffResult {
        val profile = profileDao.get()
        val existingGames = gameDao.getAll().associateBy { it.appId }
        val openSessionsByAppId = sessionDao.getAllOpenSessions().associateBy { it.appId }
        return diffAgainst(
            polls = polls,
            existingGames = existingGames,
            openSessionsByAppId = openSessionsByAppId,
            lastSyncAt = profile?.lastSyncAt ?: 0L,
            now = now,
        )
    }

    /** The only raw-write boundary. Every baseline read used here is intentionally fresh. */
    private suspend fun commitRawPoll(
        games: List<com.example.backlogium.data.remote.dto.OwnedGameDto>,
        steamId: String,
        steamLevel: Int,
        summary: com.example.backlogium.data.remote.dto.PlayerSummaryDto?,
        now: Long,
        polls: List<SessionDiffer.PollGame>,
        achievementFetch: AchievementLibraryFetch,
    ) {
        val profileBefore = profileDao.get()
        val existingGames = gameDao.getAll().associateBy { it.appId }
        val openSessionsByAppId = sessionDao.getAllOpenSessions().associateBy { it.appId }
        val diff = diffAgainst(
            polls = polls,
            existingGames = existingGames,
            openSessionsByAppId = openSessionsByAppId,
            lastSyncAt = profileBefore?.lastSyncAt ?: 0L,
            now = now,
        )

        applySessionActions(diff.actions)

        games.forEach { dto ->
            val lastPlaytime = diff.newLastPlaytime[dto.appid] ?: dto.playtimeForever
            val iconUrl = SteamIconMapper.iconUrl(dto.appid, dto.imgIconUrl)
            gameDao.insertSteamGameIfMissing(
                appId = dto.appid,
                name = dto.name,
                iconUrl = iconUrl,
                playtimeForever = dto.playtimeForever,
                playtime2Weeks = dto.playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = now,
            )
            gameDao.updateSteamFields(
                appId = dto.appid,
                name = dto.name,
                iconUrl = iconUrl,
                playtimeForever = dto.playtimeForever,
                playtime2Weeks = dto.playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = now,
            )
        }

        val goalIds = existingGames.values.filter { it.isGoal }.mapTo(mutableSetOf()) { it.appId }
        attributeDailyProgress(diff.actions, goalIds, time.zone()).forEach { (date, credit) ->
            dailyProgressDao.ensureDate(date)
            dailyProgressDao.addMinutes(date, credit.minutesPlayed, credit.goalMinutesPlayed)
        }

        if (profileBefore == null) profileDao.insertIfMissing()
        val currentProfile = profileDao.get() ?: PlayerProfile()
        val identity = mergePlayerIdentity(
            summary,
            PlayerIdentity(currentProfile.personaName, currentProfile.avatarUrl),
        )
        profileDao.updateSteamIdentity(
            steamId = steamId,
            steamLevel = steamLevel,
            personaName = identity.personaName,
            avatarUrl = identity.avatarUrl,
        )
        profileDao.updateSyncStatus(lastSyncAt = now, lastSyncError = null)

        // This is the only achievement write path for an inline poll, and it is deliberately
        // called while the same transaction still owns the raw commit.
        achievementRepository.applyRefreshes(achievementFetch.refreshes)
    }

    private fun diffAgainst(
        polls: List<SessionDiffer.PollGame>,
        existingGames: Map<Long, com.example.backlogium.data.local.entity.Game>,
        openSessionsByAppId: Map<Long, Session>,
        lastSyncAt: Long,
        now: Long,
    ): SessionDiffer.DiffResult {
        val priorStates = existingGames.mapValues { (appId, game) ->
            val open = openSessionsByAppId[appId]
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
        return if (lastSyncAt == 0L) {
            differ.baseline(polls)
        } else {
            differ.diff(
                polls = polls,
                priorStates = priorStates,
                now = now,
                previousPollAt = lastSyncAt,
            )
        }
    }

    private suspend fun persistDerived(
        today: java.time.LocalDate,
        initialConfig: com.example.backlogium.domain.VersionedRuleConfig,
    ) {
        persistVersionChecked(
            initial = initialConfig,
            readCurrent = { settings.ruleConfigWithVersionFlow.first() },
            compute = { config -> gamificationUpdater.compute(today, config) },
            persist = { result, version ->
                gamificationUpdater.persist(result, RecomputeSource.SYNC, version)
            },
            coordinator = derivedStateWrites,
        )
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
        profileDao.insertIfMissing()
        profileDao.updateLastSyncError(message)
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "steam_sync_periodic"
        const val ONE_TIME_NAME = "steam_sync_now"
    }
}
