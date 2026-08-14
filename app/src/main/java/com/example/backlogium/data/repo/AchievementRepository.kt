package com.example.backlogium.data.repo

import com.example.backlogium.data.achievement.AchievementFreshness
import com.example.backlogium.data.achievement.AchievementMerge
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.AchievementSchemaDto
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One achievement as consumers see it. [rarityPercent] is the frozen rarity snapshot — the
 * global unlock percent captured the first sync that observed the achievement unlocked, never
 * the live percent — so tier/XP derived from it cannot drift (add-steam-achievements design).
 * Null when the achievement is still locked or no percent was ever captured.
 *
 * [globalPercent] is the live percent, refreshed every sync and present regardless of unlock
 * state. It is **not** an alternative input to tier/XP — those stay pinned to [rarityPercent] —
 * but it is the only rarity signal a *locked* achievement has, so display and sorting fall back
 * to it for those rows (enhance-game-detail). [unlockedAt] is null while locked.
 */
data class GameAchievement(
    val apiName: String,
    val displayName: String,
    val iconUrl: String?,
    val unlocked: Boolean,
    val rarityPercent: Double?,
    val globalPercent: Double? = null,
    val unlockedAt: Long? = null,
    val description: String? = null,
    val hidden: Boolean = false,
)

/** An unlocked achievement with the frozen percent used for its rarity tier and its game name. */
data class UnlockedAchievementRarity(
    val appId: Long,
    val gameName: String,
    val achievementName: String,
    val rarityPercent: Double,
)

/** Network result for one game. A null achievement list means Steam returned no usable stats. */
data class AchievementRefresh(
    val appId: Long,
    val fetchedAt: Long,
    val achievements: List<PlayerAchievementDto>?,
    val globalPercentByName: Map<String, Double>,
    val schemaByName: Map<String, AchievementSchemaDto>,
    val schemaFetchedAt: Long?,
    val fullReconciliation: Boolean = false,
)

/** Write-free result of the fetch phase used by [SteamSyncWorker]. */
data class AchievementLibraryFetch(
    val selection: AchievementFreshness.Result,
    val refreshes: List<AchievementRefresh>,
)

/**
 * Owns fetching, merging, and caching Steam achievement data. Covers every game in the
 * library — not just games the player is actively engaged with — but selects what to refresh
 * by tier (hot/warm/cold/never) and applies per-data-kind freshness windows so that inline
 * sync work is bounded and library-scale work is deferred to reconciliation.
 *
 * Requests are issued serially, one in flight at a time — see [fetchGames] for why that is a
 * decision rather than an omission.
 *
 * A per-game failure (private profile, no stats, transport error) never fails the caller —
 * it is skipped and any previously stored rows for that game are left intact.
 * [CancellationException] is rethrown so WorkManager stops promptly.
 */
@Singleton
class AchievementRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val achievementDao: AchievementDao,
    private val gameAchievementSyncDao: GameAchievementSyncDao,
    private val gameDao: GameDao,
    private val time: TimeProvider,
) {

    /** Serializes merge application so a stale response cannot interleave with a newer one. */
    private val mergeMutex = Mutex()

    fun observeForGame(appId: Long): Flow<List<GameAchievement>> =
        achievementDao.observeForGame(appId).map { rows -> rows.map(Achievement::toDomain) }

    /** Unlocked/total achievement counts, keyed by appId — feeds the Library row badge. */
    val counts: Flow<Map<Long, AchievementCounts>> = achievementDao.observeCounts()
        .map { it.associateBy(AchievementCounts::appId) }

    /**
     * Per-game rarity snapshots of unlocked achievements, keyed by appId — the achievement half of
     * the Library's XP badge. [counts] cannot serve here: a count of unlocked achievements is not
     * tierable, and XP is awarded per rarity tier.
     */
    val unlockedRarityByGame: Flow<Map<Long, List<Double?>>> = achievementDao.observeUnlockedRarity()
        .map { rows -> rows.groupBy(AchievementRarity::appId) { it.snapshotPercent } }

    /**
     * Detailed all-time rarity rows for Analytics. The existing grouped percent flow remains
     * unchanged because the Library XP engine consumes that shape; this projection adds identity
     * and game names only for the Analytics drill-down.
     */
    val unlockedRarityDetails: Flow<List<UnlockedAchievementRarity>> = combine(
        achievementDao.observeUnlockedRarity(),
        gameDao.observeLibrary(),
    ) { rows, games ->
        val gameNames = games.associate { it.appId to it.name }
        rows.mapNotNull { row ->
            row.snapshotPercent?.let { percent ->
                UnlockedAchievementRarity(
                    appId = row.appId,
                    gameName = gameNames[row.appId] ?: "App ${row.appId}",
                    achievementName = row.displayName?.takeIf { it.isNotBlank() } ?: row.apiName,
                    rarityPercent = percent,
                )
            }
        }
    }

    /**
     * Achievements unlocked at or after [cutoffMillis], across every game — feeds the History
     * screen's per-day thumbnail row (regroup-history).
     */
    fun unlockedSince(cutoffMillis: Long): Flow<List<AchievementUnlock>> =
        achievementDao.observeUnlockedSince(cutoffMillis)

    /**
     * Fetches achievements for games selected by tier: hot (playtime delta), warm (recent play),
     * and a bounded number of cold/never games with no stored achievement data.
     * [apiKey]/[steamId] are passed in by the caller (the sync worker), matching [SteamApi]'s pattern.
     */
    suspend fun syncLibraryGames(
        apiKey: String,
        steamId: String,
        ownedGames: List<AchievementFreshness.OwnedGame>,
        playtimeDeltaByAppId: Map<Long, Int>,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementFreshness.Result {
        val fetched = fetchLibraryGames(
            apiKey = apiKey,
            steamId = steamId,
            ownedGames = ownedGames,
            playtimeDeltaByAppId = playtimeDeltaByAppId,
            scope = scope,
        )
        applyRefreshes(fetched.refreshes)
        return fetched.selection
    }

    /**
     * Fetch the inline achievement payloads without changing Room. Callers that have a larger
     * atomic commit use this phase first, then pass [AchievementLibraryFetch.refreshes] to
     * [applyRefreshes] from inside their transaction.
     */
    suspend fun fetchLibraryGames(
        apiKey: String,
        steamId: String,
        ownedGames: List<AchievementFreshness.OwnedGame>,
        playtimeDeltaByAppId: Map<Long, Int>,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementLibraryFetch {
        if (ownedGames.isEmpty()) {
            return AchievementLibraryFetch(
                selection = AchievementFreshness.Result(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                refreshes = emptyList(),
            )
        }

        val metadataByAppId = gameAchievementSyncDao.getAll(ownedGames.map { it.appId }.toSet())
            .associateBy { it.appId }
            .mapValues {
                AchievementFreshness.SyncMetadata(
                    it.key,
                    it.value.playerStateFetchedAt,
                    it.value.schemaFetchedAt,
                )
            }

        val selection = AchievementFreshness.selectByTier(
            now = time.nowMillis(),
            ownedGames = ownedGames,
            playtimeDeltaByAppId = playtimeDeltaByAppId,
            metadataByAppId = metadataByAppId,
        )

        val refreshes = fetchGames(
            apiKey = apiKey,
            steamId = steamId,
            appIds = selection.inlineSelected,
            schemaFetchedAtByAppId = selection.inlineSelected.associateWith {
                metadataByAppId[it]?.schemaFetchedAt
            },
            scope = scope,
        )

        return AchievementLibraryFetch(selection, refreshes)
    }

    /** Apply already-fetched achievement payloads. The caller may surround this with a Room transaction. */
    suspend fun applyRefreshes(refreshes: List<AchievementRefresh>) {
        mergeMutex.withLock {
            for (refresh in refreshes) applyRefresh(refresh)
        }
    }

    data class ReconciliationResult(
        val refreshed: Int,
        val total: Int,
    )

    data class ReconciliationFetch(
        val refreshed: Int,
        val total: Int,
    )

    /**
     * Deferred reconciliation pass: refresh every cold game (played at some point, but not warm
     * and not played since the last sync) ordered by oldest `playerStateFetchedAt` first. Each
     * successful refresh updates that timestamp, so an interrupted pass naturally resumes with
     * the games it did not yet reach rather than restarting.
     *
     * [onProgress], if given, fires after every game attempt with the running refreshed/total
     * counts — the only way a caller can know how far the pass got if it is cancelled mid-sweep.
     *
     * @return counts of games refreshed and total cold games considered
     */
    suspend fun reconcileLibraryGames(
        apiKey: String,
        steamId: String,
        scope: SyncRunRecorder.RunScope? = null,
        onProgress: ((refreshed: Int, total: Int) -> Unit)? = null,
    ): ReconciliationResult {
        val fetched = fetchReconciliationGames(
            apiKey = apiKey,
            steamId = steamId,
            scope = scope,
            onRefresh = { refresh ->
                withContext(NonCancellable) {
                    applyRefreshes(listOf(refresh))
                }
            },
            onProgress = onProgress,
        )
        return ReconciliationResult(fetched.refreshed, fetched.total)
    }

    /**
     * Fetch and hand off each full-reconciliation payload before the next game starts.
     *
     * The callback is the persistence boundary. Callers that own a larger transaction may commit
     * the one refresh there; the worker uses a non-cancellable transaction so a completed fetch is
     * never stranded in an in-memory list when WorkManager cancels the sweep.
     */
    suspend fun fetchReconciliationGames(
        apiKey: String,
        steamId: String,
        scope: SyncRunRecorder.RunScope? = null,
        onRefresh: suspend (AchievementRefresh) -> Unit,
        onProgress: ((refreshed: Int, total: Int) -> Unit)? = null,
    ): ReconciliationFetch {
        val games = gameDao.getAll()
        if (games.isEmpty()) return ReconciliationFetch(refreshed = 0, total = 0)

        val metadataByAppId = gameAchievementSyncDao.getAll(games.map { it.appId }.toSet())
            .associateBy { it.appId }

        val ownedGames = games.map {
            AchievementFreshness.OwnedGame(
                appId = it.appId,
                playtimeForever = it.playtimeForever.toLong(),
                playtime2Weeks = it.playtime2Weeks.toLong(),
            )
        }
        // Reuses tiering's own cold-tier rule so this pass's population can never drift from what
        // the inline sync considers cold; delta/metadata inputs are irrelevant to that rule.
        val cold = AchievementFreshness.selectByTier(
            now = time.nowMillis(),
            ownedGames = ownedGames,
            playtimeDeltaByAppId = emptyMap(),
            metadataByAppId = emptyMap(),
        ).cold.sortedBy { metadataByAppId[it]?.playerStateFetchedAt ?: Long.MIN_VALUE }

        // Report the total up front too, so a cancellation before any single game finishes still
        // leaves the caller with an accurate total rather than the callback's implicit initial 0.
        onProgress?.invoke(0, cold.size)
        var refreshed = 0
        fetchGames(
            apiKey = apiKey,
            steamId = steamId,
            appIds = cold,
            schemaFetchedAtByAppId = cold.associateWith { metadataByAppId[it]?.schemaFetchedAt },
            scope = scope,
            onRefresh = { refresh ->
                onRefresh(refresh)
                refreshed++
            },
            onGameDone = { refreshedSoFar -> onProgress?.invoke(refreshedSoFar, cold.size) },
            fullReconciliation = true,
        )
        return ReconciliationFetch(refreshed = refreshed, total = cold.size)
    }

    /**
     * Fetches [appIds] **serially** — one request in flight at a time.
     *
     * This is deliberate, not a missing optimisation. Tiering is what made fetch volume small: a
     * steady-state sync now selects a handful of played games, so a typical inline pass is a few
     * requests and there is nothing left for concurrency to speed up. The only pass large enough to
     * care is the weekly reconciliation, which runs on charger + unmetered wifi precisely so its
     * duration can be irrelevant. That leaves concurrency with real burst exposure against a Steam
     * client that has no retry, backoff, or 429 handling, in exchange for a speedup nothing needs —
     * and it is the same courtesy-not-throughput reasoning design.md used to pick a modest bound in
     * the first place, carried to its conclusion. The HLTB batch path serialises for the same
     * reason (it goes further and sleeps between requests).
     *
     * A per-game failure is swallowed so one bad response cannot abort the batch;
     * [CancellationException] is always rethrown so callers stop promptly. [onGameDone], if given,
     * fires after every game [syncGame] actually updates — not merely attempted — with the
     * running refreshed count.
     */
    private suspend fun fetchGames(
        apiKey: String,
        steamId: String,
        appIds: List<Long>,
        schemaFetchedAtByAppId: Map<Long, Long?>,
        scope: SyncRunRecorder.RunScope? = null,
        onRefresh: (suspend (AchievementRefresh) -> Unit)? = null,
        onGameDone: ((refreshedSoFar: Int) -> Unit)? = null,
        fullReconciliation: Boolean = false,
    ): List<AchievementRefresh> {
        val refreshes = mutableListOf<AchievementRefresh>()
        var refreshedSoFar = 0
        for (appId in appIds) {
            val refresh = try {
                fetchGame(
                    apiKey = apiKey,
                    steamId = steamId,
                    appId = appId,
                    schemaFetchedAt = schemaFetchedAtByAppId[appId],
                    scope = scope,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Per-game failure is swallowed so one bad response cannot fail the batch.
                null
            }
            if (refresh != null) {
                val completed = refresh.copy(fullReconciliation = fullReconciliation)
                if (onRefresh != null) {
                    // A persistence callback is deliberately outside the per-game failure catch:
                    // database failures must fail the worker and be retried, not be misreported as
                    // an innocuous Steam game that had no stats.
                    onRefresh(completed)
                } else {
                    refreshes += completed
                }
                refreshedSoFar++
                onGameDone?.invoke(refreshedSoFar)
            }
        }
        return refreshes
    }

    /** @return a write-free payload if this game's response is usable. */
    private suspend fun fetchGame(
        apiKey: String,
        steamId: String,
        appId: Long,
        schemaFetchedAt: Long?,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementRefresh? {
        val now = time.nowMillis()
        val playerStats = steamApi.getPlayerAchievements(apiKey, steamId, appId, scope).playerstats

        if (!playerStats.success) {
            // Private profile, no stats, or another per-app error: skip, keep last-good cache.
            // Not a refresh — playerStateFetchedAt is left untouched so this game keeps sorting
            // first in the next reconciliation pass rather than being counted as covered.
            return null
        }
        if (playerStats.achievements.isEmpty()) {
            return AchievementRefresh(
                appId = appId,
                fetchedAt = now,
                achievements = emptyList(),
                globalPercentByName = emptyMap(),
                schemaByName = emptyMap(),
                schemaFetchedAt = schemaFetchedAt,
            )
        }

        val globalPercentByName = runCatching {
            steamApi.getGlobalAchievementPercentages(appId, scope)
                .achievementpercentages.achievements
                .associate { it.name to it.percent }
        }.getOrDefault(emptyMap())

        val schemaWasStale = schemaFetchedAt == null || now - schemaFetchedAt > SCHEMA_WINDOW_MILLIS
        val schemaFetch = if (schemaWasStale) {
            runCatching {
                steamApi.getSchemaForGame(apiKey, appId, scope).game.availableGameStats?.achievements
                    ?.associateBy { it.name }
            }
        } else {
            null
        }
        val schemaByName = schemaFetch?.getOrNull().orEmpty()
        // A successful fetch is fresh even if the game genuinely has no achievement schema —
        // only a fetch that was skipped (still fresh) or that threw should leave the old
        // timestamp in place, so a real failure is retried next time rather than cached forever.
        val schemaFetchedAtNext = when {
            !schemaWasStale -> schemaFetchedAt
            schemaFetch?.isSuccess == true -> now
            else -> schemaFetchedAt
        }

        return AchievementRefresh(
            appId = appId,
            fetchedAt = now,
            achievements = playerStats.achievements,
            globalPercentByName = globalPercentByName,
            schemaByName = schemaByName,
            schemaFetchedAt = schemaFetchedAtNext,
        )
    }

    /**
     * Merge a payload inside the caller's transaction. Timestamp guards prevent an older fetch
     * that completes later from replacing a newer observation or retiring rows it did not see.
     */
    private suspend fun applyRefresh(refresh: AchievementRefresh) {
        val metadata = gameAchievementSyncDao.get(refresh.appId)
        if ((metadata?.playerStateFetchedAt ?: Long.MIN_VALUE) > refresh.fetchedAt) return

        val existing = achievementDao.getForGame(refresh.appId)
        val existingByName = existing.associateBy { it.apiName }
        val incoming = refresh.achievements ?: return
        val incomingNames = incoming.mapTo(mutableSetOf()) { it.apiName }
        val rows = if (incoming.isEmpty()) {
            emptyList()
        } else {
            incoming.mapNotNull { dto ->
                val prior = existingByName[dto.apiName]
                if (prior != null && prior.fetchedAt > refresh.fetchedAt) {
                    null
                } else {
                    com.example.backlogium.data.achievement.AchievementMerge.merge(
                        appId = refresh.appId,
                        dto = dto,
                        globalPercent = refresh.globalPercentByName[dto.apiName],
                        schema = refresh.schemaByName[dto.apiName],
                        prior = prior,
                        now = refresh.fetchedAt,
                    ).copy(retired = false)
                }
            }
        }
        val retired = if (refresh.fullReconciliation) {
            existing.filter {
                it.apiName !in incomingNames && it.fetchedAt <= refresh.fetchedAt
            }.map { it.copy(retired = true) }
        } else {
            emptyList()
        }
        val rowsToWrite = rows + retired
        if (rowsToWrite.isNotEmpty()) achievementDao.upsertAll(rowsToWrite)
        gameAchievementSyncDao.upsert(
            GameAchievementSync(
                appId = refresh.appId,
                playerStateFetchedAt = refresh.fetchedAt,
                schemaFetchedAt = refresh.schemaFetchedAt,
                hasAchievements = incoming.isNotEmpty(),
                checkedAt = refresh.fetchedAt,
            ),
        )
    }

    companion object {
        /** How long a fetched achievement schema remains valid; schema changes only when patched by the developer. */
        const val SCHEMA_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

private fun Achievement.toDomain() = GameAchievement(
    apiName = apiName,
    displayName = displayName?.takeIf { it.isNotBlank() } ?: apiName,
    iconUrl = iconUrl,
    unlocked = unlocked,
    // The snapshot, never globalPercent: the rarity-drift policy pins tier/XP to first unlock.
    rarityPercent = snapshotPercent,
    // Carried alongside for display/sorting only — locked rows have no snapshot to show or sort by.
    globalPercent = globalPercent,
    unlockedAt = unlockedAt,
    description = description?.takeIf { it.isNotBlank() },
    hidden = hidden,
)
