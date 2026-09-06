package com.example.backlogium.data.repo

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.domain.AdmissionDecision
import com.example.backlogium.domain.AdmissionFacts
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.SharedGameAdmissionPolicy
import com.example.backlogium.domain.SteamAppIdInput
import com.example.backlogium.domain.StoreVerification
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.RecomputeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A family-shared game the player removed, as Settings sees it. Never the storage entity. */
data class RemovedSharedGame(
    val appId: Long,
    val name: String,
    val removedAt: Long,
)

sealed interface PlayerDataProbe {
    data class Returned(val total: Int, val unlocked: Int) : PlayerDataProbe
    data object NoData : PlayerDataProbe
    data object Unavailable : PlayerDataProbe
}

enum class ManualImportUnavailableAt { OWNED_LIBRARY, STORE }

sealed interface ManualSharedGameImportResult {
    data object InvalidInput : ManualSharedGameImportResult
    data class Owned(val appId: Long, val name: String) : ManualSharedGameImportResult
    data class Excluded(val appId: Long) : ManualSharedGameImportResult
    data class NotAGame(val appId: Long) : ManualSharedGameImportResult
    data class Unavailable(val appId: Long, val at: ManualImportUnavailableAt) : ManualSharedGameImportResult
    data class Imported(
        val appId: Long,
        val name: String,
        val alreadyTracked: Boolean,
        val playerData: PlayerDataProbe,
    ) : ManualSharedGameImportResult
}

/**
 * Admission, removal, and re-admission of family-shared games (add-family-shared-games).
 *
 * A borrowed game is absent from `GetOwnedGames` and therefore from every path that creates a game
 * row today, so the only evidence it exists is that Steam reports the player inside it. This
 * repository turns that observation into a tracked game — or explains why it did not — and owns the
 * sticky exclusion list that a removal writes to.
 *
 * The decision itself lives in [SharedGameAdmissionPolicy], a pure function; everything here is the
 * IO around it.
 */
@Singleton
class FamilySharedGameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val excludedDao: ExcludedSharedGameDao,
    private val profileDao: PlayerProfileDao,
    private val settings: SettingsDataStore,
    private val store: SteamStoreAppDataSource,
    private val genres: GameGenreRepository,
    private val policy: SharedGameAdmissionPolicy,
    private val notifier: SharedGameNotifier,
    private val steamApi: SteamApi,
    private val time: TimeProvider,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val transaction: DatabaseTransactionScope,
    private val gamificationUpdater: GamificationUpdater,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val achievementRepository: AchievementRepository,
) {

    /** Removed games, newest first. Empty when nothing has been removed, so Settings can hide. */
    val removedGames: Flow<List<RemovedSharedGame>> = excludedDao.observeAll().map { rows ->
        rows.map { RemovedSharedGame(it.appId, it.name, it.excludedAt) }
    }

    /**
     * Consider an app id observed in presence that has no `games` row.
     *
     * Safe and cheap to call on every observation: the first call records the app id as a candidate
     * and stops, and only a later call — after a sync has since confirmed the game is genuinely not
     * owned — reaches the one store request. Every rejection is reported rather than swallowed so
     * the caller can leave the observation alone and reconsider it next time.
     *
     * @return the decision reached. [AdmissionDecision.Admit] means a game row now exists.
     */
    suspend fun considerAdmission(appId: Long, observedAt: Long): AdmissionDecision {
        if (settings.isSharedGameNotAGame(appId)) {
            clearCandidateIfCurrent(appId)
            return AdmissionDecision.NotAGame
        }
        val candidate = settings.sharedGameCandidateFlow.first()
        // A new app id restarts the clock: the sync that matters is one that completed after *this*
        // id was first seen, and the previous candidate is worth nothing once play has moved on.
        val firstObservedAt = candidate?.takeIf { it.appId == appId }?.firstObservedAt
            ?: observedAt.also { settings.setSharedGameCandidate(appId, it) }

        val facts = AdmissionFacts(
            appId = appId,
            isTracked = gameDao.getById(appId) != null,
            isExcluded = excludedDao.isExcluded(appId),
            firstObservedAt = firstObservedAt,
            lastSuccessfulSyncAt = profileDao.get()?.lastSyncAt ?: 0L,
        )

        val screened = policy.evaluate(facts)
        if (screened != AdmissionDecision.NeedsStoreVerification) {
            // Nothing further will change for a permanently rejected id; stop carrying it as a
            // candidate so a later, genuinely new observation isn't measured against a stale clock.
            if (screened == AdmissionDecision.AlreadyTracked || screened == AdmissionDecision.Excluded) {
                clearCandidateIfCurrent(appId)
            }
            return screened
        }

        val info = store.appInfoFor(appId)
        val verified = policy.evaluate(
            facts.copy(
                store = when (info) {
                    is StoreAppInfo.Game -> StoreVerification.GAME
                    StoreAppInfo.NotAGame -> StoreVerification.NOT_A_GAME
                    is StoreAppInfo.Unavailable -> StoreVerification.UNAVAILABLE
                },
            ),
        )
        if (info == StoreAppInfo.NotAGame) {
            settings.markSharedGameNotAGame(appId)
            clearCandidateIfCurrent(appId)
            return verified
        }
        if (verified != AdmissionDecision.Admit || info !is StoreAppInfo.Game) return verified

        admit(appId, info, observedAt)
        return AdmissionDecision.Admit
    }

    suspend fun importManually(
        input: String,
        apiKey: String,
        steamId: String,
    ): ManualSharedGameImportResult {
        val appId = SteamAppIdInput.parse(input)
            ?: return ManualSharedGameImportResult.InvalidInput
        gameDao.getById(appId)?.let { tracked ->
            return if (tracked.source == GameSource.STEAM_OWNED) {
                ManualSharedGameImportResult.Owned(appId, tracked.name)
            } else {
                importedResult(appId, tracked.name, true, apiKey, steamId)
            }
        }
        if (excludedDao.isExcluded(appId)) return ManualSharedGameImportResult.Excluded(appId)
        if (settings.isSharedGameNotAGame(appId)) return ManualSharedGameImportResult.NotAGame(appId)
        return importUntracked(appId, apiKey, steamId)
    }

    private suspend fun importUntracked(appId: Long, apiKey: String, steamId: String): ManualSharedGameImportResult {
        val owned = try {
            steamApi.getOwnedGames(apiKey, steamId).response.games
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ManualSharedGameImportResult.Unavailable(appId, ManualImportUnavailableAt.OWNED_LIBRARY)
        }
        owned.firstOrNull { it.appid == appId }?.let {
            return ManualSharedGameImportResult.Owned(appId, it.name.ifBlank { "App $appId" })
        }
        return verifyStoreAndImport(appId, apiKey, steamId)
    }

    private suspend fun verifyStoreAndImport(appId: Long, apiKey: String, steamId: String): ManualSharedGameImportResult {
        val info = try {
            store.appInfoFor(appId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StoreAppInfo.Unavailable()
        }
        when (info) {
            StoreAppInfo.NotAGame -> {
                settings.markSharedGameNotAGame(appId)
                clearCandidateIfCurrent(appId)
                return ManualSharedGameImportResult.NotAGame(appId)
            }
            is StoreAppInfo.Unavailable -> return ManualSharedGameImportResult.Unavailable(
                appId,
                ManualImportUnavailableAt.STORE,
            )
            is StoreAppInfo.Game -> Unit
        }
        admit(appId, info, time.nowMillis(), announce = false)
        return importedResult(appId, info.name, false, apiKey, steamId)
    }

    /**
     * Remove a family-shared game and record the exclusion, so further play does not re-admit it.
     * The game row is deleted, and its sessions cascade with it — the player asked for the game to
     * stop being tracked, and leaving orphaned history behind would contradict that while still
     * feeding XP totals.
     *
     * A no-op for a game whose source is owned: `deleteSharedGame` is guarded in SQL, and the
     * exclusion is only written when a row was actually removed. The library's contents are Steam's
     * to decide, not the app's.
     *
     * @return true when a family-shared game was removed.
     */
    suspend fun remove(appId: Long): Boolean {
        val removed = transaction.run {
            val game = gameDao.getById(appId) ?: return@run false
            val sessions = sessionDao.getAll().filter { it.appId == appId }
            if (gameDao.deleteSharedGame(appId) == 0) return@run false
            reconcileDailyProgress(game, sessions)
            excludedDao.upsert(
                ExcludedSharedGame(appId = appId, name = game.name, excludedAt = time.nowMillis()),
            )
            true
        }
        if (!removed) return false
        clearCandidateIfCurrent(appId)
        recomputeAdministratively()
        return true
    }
    private suspend fun reconcileDailyProgress(game: Game, sessions: List<Session>) {
        val zone = time.zone()
        val minutesByDate = sessions
            .groupBy { session ->
                Instant.ofEpochMilli(session.startAt).atZone(zone).toLocalDate().toString()
            }
            .mapValues { (_, rows) -> rows.sumOf { it.minutes } }

        minutesByDate.forEach { (date, minutes) ->
            val day = dailyProgressDao.getByDate(date) ?: return@forEach
            val removedGoalMinutes = if (game.isGoal) minutes else 0
            dailyProgressDao.setMinutes(
                date = date,
                minutesPlayed = (day.minutesPlayed - minutes).coerceAtLeast(0),
                goalMinutesPlayed = (day.goalMinutesPlayed - removedGoalMinutes).coerceAtLeast(0),
            )
        }
    }

    /**
     * Removal is a bookkeeping action, not play: the resulting level, streak, or quest change is
     * real but not earned, so it declares [RecomputeSource.GAME_REMOVAL] rather than [RecomputeSource.SYNC]
     * and reseeds the delivery baseline instead of producing progress events
     * (auditfix-session-ledger-integrity, #104; progress-events spec's non-earned-provenance
     * requirement).
     */
    private suspend fun recomputeAdministratively() {
        derivedStateWrites.withLock {
            val rules = settings.ruleConfigWithVersionFlow.first()
            gamificationUpdater.recompute(
                today = time.today(),
                source = RecomputeSource.GAME_REMOVAL,
                config = rules.config,
                configVersion = rules.version,
            )
        }
    }

    /**
     * Reverse a removal from Settings. Restore the tracked row immediately so the game is visible
     * in Library and can be added to collections again; future play observations will provide its
     * sessions through the normal presence path. The exclusion and row are changed together so a
     * restored game cannot be admitted twice if presence is observed at the same time.
     */
    suspend fun reverseRemoval(appId: Long): Boolean {
        val restored = transaction.run {
            val excluded = excludedDao.getAll().firstOrNull { it.appId == appId } ?: return@run false
            gameDao.insertSharedGameIfMissing(
                appId = excluded.appId,
                name = excluded.name,
                iconUrl = "",
                admittedAt = time.nowMillis(),
            )
            excludedDao.delete(appId)
            true
        }
        if (restored) {
            clearCandidateIfCurrent(appId)
            // Equally non-earned as the removal it undoes: the player did not earn the restored
            // progress by playing (progress-events spec's "Reversing a removal is equally
            // non-earned" scenario).
            recomputeAdministratively()
        }
        return restored
    }

    private suspend fun admit(
        appId: Long,
        info: StoreAppInfo.Game,
        observedAt: Long,
        announce: Boolean = true,
    ) {
        // Icon hash comes from GetOwnedGames, which by definition has nothing to say about a
        // borrowed game. Left blank: header and capsule artwork are derived from the app id alone,
        // so the game still arrives with artwork, and every icon consumer already treats a blank
        // icon as "use the derived art".
        gameDao.insertSharedGameIfMissing(
            appId = appId,
            name = info.name,
            iconUrl = "",
            admittedAt = observedAt,
        )
        // One announcement per admission. `INSERT OR IGNORE` above makes a second observation of an
        // already-admitted game a no-op, and the policy's AlreadyTracked screen means this line is
        // not reached for one at all — a game observed again is rejected before any of this runs.
        // Best-effort: a genre-cache write failure must not undo an otherwise-valid admission,
        // and background enrichment would resolve them later anyway.
        runCatching { genres.storeGenres(appId, info.genres) }
        clearCandidateIfCurrent(appId)
        if (announce && !notifier.notifyAdmitted(appId, info.name)) {
            settings.setSharedGameAnnouncement(appId, info.name, time.nowMillis())
        }
    }

    private suspend fun importedResult(
        appId: Long,
        name: String,
        alreadyTracked: Boolean,
        apiKey: String,
        steamId: String,
    ) = ManualSharedGameImportResult.Imported(
        appId,
        name,
        alreadyTracked,
        probePlayerData(apiKey, steamId, appId),
    )

    /**
     * Fetches this game's achievements and persists them through [AchievementRepository]'s normal
     * merge path — not a throwaway probe. Manual import previously fetched achievement data only
     * to summarize it in a toast and discarded the result, leaving the detail screen with nothing
     * to show even though the player was told achievements were found
     * (fix-shared-game-achievement-visibility).
     */
    private suspend fun probePlayerData(apiKey: String, steamId: String, appId: Long): PlayerDataProbe =
        when (val refresh = achievementRepository.refreshOne(apiKey, steamId, appId)) {
            is SingleGameRefresh.Persisted -> PlayerDataProbe.Returned(refresh.total, refresh.unlocked)
            SingleGameRefresh.NoUsableData -> PlayerDataProbe.NoData
            SingleGameRefresh.Unavailable -> PlayerDataProbe.Unavailable
        }

    private suspend fun clearCandidateIfCurrent(appId: Long) {
        if (settings.sharedGameCandidateFlow.first()?.appId == appId) {
            settings.clearSharedGameCandidate()
        }
    }
}
