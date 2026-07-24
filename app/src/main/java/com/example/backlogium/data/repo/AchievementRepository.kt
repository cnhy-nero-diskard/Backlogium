package com.example.backlogium.data.repo

import com.example.backlogium.data.achievement.AchievementFreshness
import com.example.backlogium.data.achievement.AchievementMerge
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.NO_ACHIEVEMENTS_MARKER
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns fetching, merging, and caching Steam achievement data. Scoped and freshness-gated
 * (add-steam-achievements design): only games the player engages with — those with tracked
 * sessions plus goal-tagged games — are fetched, and only when stale or missing, bounding
 * per-sync API volume the same way [HltbRepository] bounds its batch sweep.
 *
 * A per-game failure (private profile, no stats, transport error) never fails the caller —
 * it is skipped and any previously stored rows for that game are left intact.
 */
@Singleton
class AchievementRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val achievementDao: AchievementDao,
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val time: TimeProvider,
) {

    fun observeForGame(appId: Long): Flow<List<Achievement>> = achievementDao.observeForGame(appId)

    /** Unlocked/total achievement counts, keyed by appId — feeds the Library row badge. */
    val counts: Flow<Map<Long, AchievementCounts>> = achievementDao.observeCounts()
        .map { it.associateBy(AchievementCounts::appId) }

    /**
     * Fetches achievements for every in-scope game whose data is stale or missing. [apiKey]/
     * [steamId] are passed in by the caller (the sync worker), matching [SteamApi]'s pattern.
     */
    suspend fun syncInScopeGames(apiKey: String, steamId: String) {
        val playedIds = sessionDao.trackedMinutesByGame().map { it.appId }
        val goalIds = gameDao.goalAppIds()
        val inScope = (playedIds + goalIds).distinct()
        if (inScope.isEmpty()) return

        val fetchedAtByAppId = achievementDao.fetchedAtByApp()
            .associate { it.appId to it.fetchedAt }
        val stale = AchievementFreshness.selectStaleOrMissing(
            now = time.nowMillis(),
            window = FRESHNESS_WINDOW_MILLIS,
            appIds = inScope,
            fetchedAtByAppId = fetchedAtByAppId,
        )

        for (appId in stale) {
            runCatching { syncGame(apiKey, steamId, appId) }
        }
    }

    private suspend fun syncGame(apiKey: String, steamId: String, appId: Long) {
        val now = time.nowMillis()
        val playerStats = steamApi.getPlayerAchievements(apiKey, steamId, appId).playerstats

        if (!playerStats.success) {
            // Private profile, no stats, or another per-app error: skip, keep last-good cache.
            return
        }
        if (playerStats.achievements.isEmpty()) {
            achievementDao.upsertAll(
                listOf(Achievement(appId = appId, apiName = NO_ACHIEVEMENTS_MARKER, fetchedAt = now)),
            )
            return
        }

        val globalPercentByName = runCatching {
            steamApi.getGlobalAchievementPercentages(appId)
                .achievementpercentages.achievements
                .associate { it.name to it.percent }
        }.getOrDefault(emptyMap())

        val schemaByName = runCatching {
            steamApi.getSchemaForGame(apiKey, appId).game.availableGameStats?.achievements
                ?.associateBy { it.name }
        }.getOrNull().orEmpty()

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
        achievementDao.deleteMarker(appId)
    }

    companion object {
        /** Bounds achievement-fetch volume: refreshed roughly hourly per in-scope game. */
        const val FRESHNESS_WINDOW_MILLIS = 60L * 60 * 1000
    }
}
