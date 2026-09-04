package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.backup.BackupRepository
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.AchievementLibraryFetch
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.remote.dto.lastPlayedAtMillis
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.LibraryRecency
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.PlayerIdentity
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.SharedGameConverter
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

/**
 * Keep the owned-games poll independent from the optional fine-grained presence decision. A
 * worker can discover a game while backgrounded, but its callback can only record that a start was
 * not attempted; the Steam data fetch must still be allowed to complete and determine the worker's
 * own result.
 */
internal suspend fun <T> fetchOwnedGamesAfterPresenceDecision(
    gameDetected: Boolean,
    recordPresenceNotAttempted: suspend () -> Unit,
    fetchOwnedGames: suspend () -> T,
): kotlin.Result<T> {
    if (gameDetected) {
        try {
            recordPresenceNotAttempted()
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

/** Minutes credited to one local calendar date by a sync poll. */
internal data class DailyProgressCredit(
    val minutesPlayed: Int,
    val goalMinutesPlayed: Int,
)

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
 * What one poll writes for one observed game's three recency columns.
 *
 * A null [returnedToPlayAt] means "record nothing and leave any stored return alone", not "clear
 * it" — the write path's `COALESCE` enforces that. A null [firstSeenAt] likewise means "do not
 * stamp an arrival", which for an inserted row is the positive statement that the game was already
 * here (a baseline poll) rather than a missing value.
 */
internal data class RecencyPollWrite(
    val firstSeenAt: Long?,
    val lastPlayedAt: Long?,
    val returnedToPlayAt: Long?,
)

/**
 * One observed game's recency writes, resolved before any of them is stored.
 *
 * Every input is supplied: this reads no clock and no database, which is what makes the poll's
 * ordering hazard testable. The hazard is that [storedLastPlayedAt] and [mostRecentSessionEndAt]
 * both describe the state *before* this poll — the caller must read them ahead of both its session
 * writes and its `lastPlayedAt` update, because each of those destroys the evidence that there was
 * a gap at all.
 *
 * @param isBaseline whether this poll is establishing the library rather than observing a change to
 *   one — meaning it found no stored library and no completed sync exists yet. Deliberately not
 *   simply "is this the first sync": an upgrade and a restore both leave a known library behind, and
 *   neither may present the games it already contains as newly acquired while still stamping genuine
 *   new ones.
 * @param observedPlayAt when the play happened, as the caller knows it — Steam's newly reported
 *   last-played time for a periodic poll, or an event time supplied by a post-play caller. A caller
 *   with no event-time estimate passes null. Never derived here: see [LibraryRecency.evaluateReturn].
 */
internal fun recencyPollWrite(
    isBaseline: Boolean,
    isNewToLibrary: Boolean,
    hadPlayIncrease: Boolean,
    storedLastPlayedAt: Long?,
    mostRecentSessionEndAt: Long?,
    reportedPlayAt: Long?,
    observedPlayAt: Long?,
    now: Long,
): RecencyPollWrite = RecencyPollWrite(
    // A baseline poll stamps nothing: the library it is discovering is one the player already
    // owned, and presenting it as newly acquired is the exact failure this rule exists to prevent.
    // Existing rows are never reached at all — the insert is `INSERT OR IGNORE`.
    firstSeenAt = now.takeIf { !isBaseline && isNewToLibrary },
    // Steam-owned, so written on every poll including a baseline: a last-played time describes
    // history that already happened rather than a change, and null is a faithful "unknown".
    lastPlayedAt = reportedPlayAt,
    returnedToPlayAt = if (isBaseline || !hadPlayIncrease) {
        null
    } else {
        LibraryRecency.evaluateReturn(
            previousLastPlayedAt = storedLastPlayedAt,
            mostRecentSessionEndAt = mostRecentSessionEndAt,
            observedPlayAt = observedPlayAt,
            now = now,
        )
    },
)
internal fun isLibraryBaseline(existingGameCount: Int, lastSyncAt: Long): Boolean =
    existingGameCount == 0 && lastSyncAt <= 0L

/**
 * Steam uses an empty response envelope for profiles whose libraries cannot be read. Only its
 * explicit `game_count: 0` confirms that an empty list is a successful library snapshot; persisting
 * that snapshot establishes the durable baseline for the first later acquisition.
 */
internal fun shouldPersistOwnedGamesPoll(gameCount: Int?, gamesAreEmpty: Boolean): Boolean =
    !gamesAreEmpty || gameCount == 0

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
    private val accountChangeMarker: AccountChangeMarkerStore,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val committer: PlaytimeObservationCommitter,
    private val sharedGameConverter: SharedGameConverter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        syncCoordinator.withLock {
            // The account-change marker is the durable barrier between old credentials and old
            // Room state. A worker that arrives after the marker is written must not poll or diff;
            // the coordinator owns the reset and startup recovery.
            if (accountChangeMarker.pendingSteamId() != null) {
                return@withLock Result.success()
            }
            doWorkLocked()
        }

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
            val storedSteamId = profileDao.get()?.steamId?.takeIf { it.isNotBlank() }
            if (!canDiffAgainstAccount(storedSteamId, steamId)) {
                recordError("Stored library belongs to a different Steam account; confirm the account change first")
                outcome = SyncOutcome.SKIPPED_ACCOUNT_MISMATCH
                return Result.success()
            }
            // Presence first, before any library-scale work. This one request carries both the
            // running game and the profile header identity; best-effort, so a failure yields null
            // and mergePlayerIdentity below keeps the stored values.
            val summary = runCatching {
                steamApi.getPlayerSummaries(apiKey, steamId, scope = scope).response.players.firstOrNull()
            }.getOrNull()

            // The detection path for a game that started while the app was never opened: record
            // that the foreground-only start was not attempted. Deliberately ahead of
            // getOwnedGames — presence doesn't depend on the owned-games list, so neither a
            // private library nor a failure later in this run may cost the player detection.
            val owned = fetchOwnedGamesAfterPresenceDecision(
                gameDetected = !summary?.gameId.isNullOrBlank(),
                recordPresenceNotAttempted = {
                    presenceServiceStarter.recordNotAttempted(trigger = "sync")
                    Unit
                },
                fetchOwnedGames = {
                    steamApi.getOwnedGames(apiKey, steamId, scope = scope)
                },
            ).getOrThrow()
            val games = owned.response.games

            if (!shouldPersistOwnedGamesPoll(owned.response.gameCount, games.isEmpty())) {
                // An unconfirmed empty envelope usually means a private profile. Keep last-good
                // data; an explicit `game_count: 0` continues through persistPoll so the completed
                // empty-library baseline is durable.
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
        //
        // Achievement progress on a borrowed game is recorded against the player's own account, so
        // a family-shared game belongs in this scope exactly like an owned one — GetPlayerAchievements
        // keys on app id, not on ownership. Its tiering input is the playtime the app actually knows
        // (tracked session minutes), since Steam reports none — but that local figure can be zero
        // for a game played or even completed before the app ever observed it, so it is tagged
        // FAMILY_SHARED rather than trusted the way an owned game's Steam-reported total is:
        // AchievementFreshness never excludes a shared game from missing-data eligibility on the
        // strength of zero tracked minutes alone (fix-shared-game-achievement-visibility).
        val trackedByAppId = sessionDao.trackedMinutesByGame().associate { it.appId to it.minutes }
        val achievementScope = games.map {
            com.example.backlogium.data.achievement.AchievementFreshness.OwnedGame(
                appId = it.appid,
                playtimeForever = it.playtimeForever.toLong(),
                playtime2Weeks = it.playtime2Weeks.toLong(),
                source = com.example.backlogium.domain.GameSource.STEAM_OWNED,
            )
        } + gameDao.sharedGames().map { shared ->
            com.example.backlogium.data.achievement.AchievementFreshness.OwnedGame(
                appId = shared.appId,
                playtimeForever = (trackedByAppId[shared.appId] ?: 0).toLong(),
                playtime2Weeks = 0L,
                source = com.example.backlogium.domain.GameSource.FAMILY_SHARED,
            )
        }
        val achievementFetch = achievementRepository.fetchLibraryGames(
            apiKey = apiKey,
            steamId = steamId,
            ownedGames = achievementScope,
            playtimeDeltaByAppId = provisionalDiff.playedDeltaByAppId,
            scope = scope,
        )
        scope.recordTiers(
            hot = achievementFetch.selection.hot.size,
            warm = achievementFetch.selection.warm.size,
            cold = achievementFetch.selection.cold.size,
            never = achievementFetch.selection.never.size,
        )

        val arrivedAppIds = withContext(NonCancellable) {
            database.withTransaction {
                commitRawPoll(
                    games = games,
                    steamId = steamId,
                    steamLevel = steamLevel,
                    summary = summary,
                    now = now,
                    achievementFetch = achievementFetch,
                )
            }
        }

        // The arrivals the commit itself stamped, carried out rather than queried again: a second
        // read would have to guess which of the games now carrying a `firstSeenAt` this poll wrote.
        // Written after the transaction, never inside it — DataStore is a separate store, and this
        // codebase does not wrap cross-store writes in a Room transaction. A poll that stamped
        // nothing leaves the existing announcement exactly as it stood.
        if (arrivedAppIds.isNotEmpty()) {
            runCatching { settings.setAcquiredGames(arrivedAppIds, now) }
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
        val existingGames = gameDao.ownedGamesForDiffing().associateBy { it.appId }
        val openSessionsByAppId = sessionDao.getAllOpenSessions().associateBy { it.appId }
        return diffAgainst(
            polls = polls,
            existingGames = existingGames,
            openSessionsByAppId = openSessionsByAppId,
            lastSyncAt = profile?.lastSyncAt ?: 0L,
            now = now,
        )
    }

    /**
     * The only raw-write boundary. Every baseline read used here is intentionally fresh.
     *
     * Returns the app ids this poll stamped as arrivals, so the acquisition announcement is driven
     * by what the commit actually wrote.
     */
    private suspend fun commitRawPoll(
        games: List<com.example.backlogium.data.remote.dto.OwnedGameDto>,
        steamId: String,
        steamLevel: Int,
        summary: com.example.backlogium.data.remote.dto.PlayerSummaryDto?,
        now: Long,
        achievementFetch: AchievementLibraryFetch,
    ): Set<Long> {
        val profileBefore = profileDao.get()
        // A shared game the player has since bought converts before the diff reads its baseline,
        // so the poll that first reports it as owned already sees the fresh baseline and produces
        // no session from a lifetime total that was earned while borrowing.
        sharedGameConverter.convertNewlyOwned(games, now)
        // Only games Steam reports playtime for are diffed. The partition between the two session
        // mechanisms is this query, not a check inside either mechanism.
        val existingGames = gameDao.ownedGamesForDiffing().associateBy { it.appId }
        val lastSyncAt = profileBefore?.lastSyncAt ?: 0L
        // The recency baseline is "tracking has not begun", which is a different question from the
        // session differ's playtime baseline (also keyed on `lastSyncAt`).
        //
        // An empty stored library is a baseline only when no successful sync has committed yet.
        // Once an empty poll commits, `lastSyncAt` makes that known-empty result durable and a later
        // acquisition is genuine. A non-empty stored library is always known, keeping upgrade and
        // restore behavior unchanged: existing app ids are never arrivals, while an app id missing
        // from a restore is a genuine acquisition and is announced even when restored `lastSyncAt`
        // is zero.
        val isLibraryBaseline = isLibraryBaseline(
            existingGameCount = existingGames.size,
            lastSyncAt = lastSyncAt,
        )
        // Read before `applySessionActions`, deliberately. Both of this poll's own writes — the
        // session it is about to open or extend, and the `lastPlayedAt` it is about to overwrite —
        // destroy the only evidence that the play it observed followed a dormant period. Reading
        // either afterwards would compare the new play against itself.
        val sessionInstantBefore = sessionDao.latestSessionInstantByGame()
            .associate { it.appId to it.at }
        val committed = committer.commit(
            observed = games.map { dto ->
                PlaytimeObservationCommitter.ObservedGame(
                    appId = dto.appid,
                    name = dto.name,
                    iconUrl = SteamIconMapper.iconUrl(dto.appid, dto.imgIconUrl),
                    playtimeForever = dto.playtimeForever,
                    playtime2Weeks = dto.playtime2Weeks,
                )
            },
            observedPlayAt = now,
            syncedAt = now,
        )

        val arrivedAppIds = mutableSetOf<Long>()
        games.forEach { dto ->
            val reportedPlayAt = dto.lastPlayedAtMillis
            val recency = recencyPollWrite(
                isBaseline = isLibraryBaseline,
                isNewToLibrary = dto.appid !in existingGames,
                hadPlayIncrease = (committed.playedDeltaByAppId[dto.appid] ?: 0) > 0,
                storedLastPlayedAt = existingGames[dto.appid]?.lastPlayedAt,
                mostRecentSessionEndAt = sessionInstantBefore[dto.appid],
                reportedPlayAt = reportedPlayAt,
                // This is a periodic poll. If Steam omitted its event timestamp, do not turn the
                // observation instant into a fabricated return or a badge-window start.
                observedPlayAt = reportedPlayAt,
                now = now,
            )
            if (recency.firstSeenAt != null) arrivedAppIds += dto.appid
            gameDao.updateRecencyFields(
                appId = dto.appid,
                firstSeenAt = recency.firstSeenAt,
                lastPlayedAt = recency.lastPlayedAt,
                returnedToPlayAt = recency.returnedToPlayAt,
            )
        }

        if (profileBefore == null) profileDao.insertIfMissing()
        val currentProfile = profileDao.get() ?: PlayerProfile()
        val identity = mergePlayerIdentity(
            summary,
            PlayerIdentity(
                currentProfile.personaName,
                currentProfile.avatarUrl,
                currentProfile.storeRegion,
            ),
        )
        profileDao.updateSteamIdentity(
            steamId = steamId,
            steamLevel = steamLevel,
            personaName = identity.personaName,
            avatarUrl = identity.avatarUrl,
            storeRegion = identity.storeRegion,
        )
        profileDao.updateSyncStatus(lastSyncAt = now, lastSyncError = null)

        // This is the only achievement write path for an inline poll, and it is deliberately
        // called while the same transaction still owns the raw commit.
        achievementRepository.applyRefreshes(achievementFetch.refreshes)

        return arrivedAppIds
    }

    private fun diffAgainst(
        polls: List<SessionDiffer.PollGame>,
        existingGames: Map<Long, com.example.backlogium.data.local.entity.Game>,
        openSessionsByAppId: Map<Long, com.example.backlogium.data.local.entity.Session>,
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

    private suspend fun recordError(message: String) {
        profileDao.insertIfMissing()
        profileDao.updateLastSyncError(message)
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "steam_sync_periodic"
        const val ONE_TIME_NAME = "steam_sync_now"
    }
}

/** A stored playtime baseline is usable only for the same configured Steam account. */
internal fun canDiffAgainstAccount(storedSteamId: String?, pollSteamId: String): Boolean =
    storedSteamId.isNullOrBlank() || storedSteamId == pollSteamId
