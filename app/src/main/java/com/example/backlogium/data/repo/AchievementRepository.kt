package com.example.backlogium.data.repo

import com.example.backlogium.data.achievement.AchievementFreshness
import com.example.backlogium.data.achievement.AchievementMerge
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/**
 * Owns fetching, merging, and caching Steam achievement data. Covers every game in the
 * library — not just games the player is actively engaged with — but selects what to refresh
 * by tier (hot/warm/cold/never) and applies per-data-kind freshness windows so that inline
 * sync work is bounded and library-scale work is deferred to reconciliation.
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

    /**
     * Caps concurrent achievement requests so a large hot/warm batch cannot pile up
     * in-flight calls against Steam. Matches the bounded-concurrency pattern used for
     * HLTB batch refreshes.
     */
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

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
    ): AchievementFreshness.Result {
        if (ownedGames.isEmpty()) {
            return AchievementFreshness.Result(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
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

        for (appId in selection.inlineSelected) {
            val metadata = metadataByAppId[appId]
            try {
                fetchSemaphore.withPermit {
                    syncGame(
                        apiKey = apiKey,
                        steamId = steamId,
                        appId = appId,
                        schemaFetchedAt = metadata?.schemaFetchedAt,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Per-game failure is swallowed so one bad response cannot fail the sync.
            }
        }

        return selection
    }

    data class ReconciliationResult(
        val refreshed: Int,
        val total: Int,
    )

    /**
     * Deferred reconciliation pass: refresh every cold game (played at some point, but not warm
     * and not played since the last sync) ordered by oldest `playerStateFetchedAt` first. Each
     * successful refresh updates that timestamp, so an interrupted pass naturally resumes with
     * the games it did not yet reach rather than restarting.
     *
     * @return counts of games refreshed and total cold games considered
     */
    suspend fun reconcileLibraryGames(
        apiKey: String,
        steamId: String,
    ): ReconciliationResult {
        val games = gameDao.getAll()
        if (games.isEmpty()) return ReconciliationResult(0, 0)

        val metadataByAppId = gameAchievementSyncDao.getAll(games.map { it.appId }.toSet())
            .associateBy { it.appId }

        val cold = games
            .filter { it.playtimeForever > 0 && it.playtime2Weeks == 0 }
            .map {
                it.appId to AchievementFreshness.SyncMetadata(
                    it.appId,
                    metadataByAppId[it.appId]?.playerStateFetchedAt,
                    metadataByAppId[it.appId]?.schemaFetchedAt,
                )
            }
            .sortedWith(compareBy { it.second.playerStateFetchedAt ?: Long.MIN_VALUE })
            .map { it.first }

        var refreshed = 0
        for (appId in cold) {
            try {
                fetchSemaphore.withPermit {
                    syncGame(
                        apiKey = apiKey,
                        steamId = steamId,
                        appId = appId,
                        schemaFetchedAt = metadataByAppId[appId]?.schemaFetchedAt,
                    )
                }
                refreshed++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Per-game failure is swallowed; keep going so one bad game doesn't abort the pass.
            }
        }
        return ReconciliationResult(refreshed, cold.size)
    }

    private suspend fun syncGame(
        apiKey: String,
        steamId: String,
        appId: Long,
        schemaFetchedAt: Long?,
    ) {
        val now = time.nowMillis()
        val playerStats = steamApi.getPlayerAchievements(apiKey, steamId, appId).playerstats

        if (!playerStats.success) {
            // Private profile, no stats, or another per-app error: skip, keep last-good cache.
            return
        }
        if (playerStats.achievements.isEmpty()) {
            gameAchievementSyncDao.upsert(
                GameAchievementSync(
                    appId = appId,
                    playerStateFetchedAt = now,
                    schemaFetchedAt = schemaFetchedAt,
                    hasAchievements = false,
                    checkedAt = now,
                ),
            )
            return
        }

        val globalPercentByName = runCatching {
            steamApi.getGlobalAchievementPercentages(appId)
                .achievementpercentages.achievements
                .associate { it.name to it.percent }
        }.getOrDefault(emptyMap())

        val schemaByName = if (schemaFetchedAt == null || now - schemaFetchedAt > SCHEMA_WINDOW_MILLIS) {
            runCatching {
                steamApi.getSchemaForGame(apiKey, appId).game.availableGameStats?.achievements
                    ?.associateBy { it.name }
            }.getOrNull()
        } else {
            null
        }.orEmpty()

        val existingByName = achievementDao.getForGame(appId).associateBy { it.apiName }

        val rows = playerStats.achievements.map { dto ->
            AchievementMerge.merge(
                appId = appId,
                dto = dto,
                globalPercent = globalPercentByName[dto.apiName],
                schema = schemaByName[dto.apiName],
                prior = existingByName[dto.apiName],
                now = now,
            )
        }
        achievementDao.upsertAll(rows)
        gameAchievementSyncDao.upsert(
            GameAchievementSync(
                appId = appId,
                playerStateFetchedAt = now,
                schemaFetchedAt = if (schemaByName.isEmpty()) schemaFetchedAt else now,
                hasAchievements = true,
                checkedAt = now,
            ),
        )
    }

    companion object {
        /** How long a fetched achievement schema remains valid; schema changes only when patched by the developer. */
        const val SCHEMA_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Maximum concurrent achievement fetches within a single sync/reconciliation pass. */
        const val MAX_CONCURRENT_FETCHES = 5
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
