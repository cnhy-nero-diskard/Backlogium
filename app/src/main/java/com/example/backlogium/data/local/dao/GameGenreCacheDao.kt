package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.GameGenreCache
import kotlinx.coroutines.flow.Flow

@Dao
interface GameGenreCacheDao {

    @Upsert
    suspend fun upsert(cache: GameGenreCache)

    @Query("DELETE FROM game_genre_cache")
    suspend fun deleteAll()

    @Query("SELECT * FROM game_genre_cache")
    fun observeAll(): Flow<List<GameGenreCache>>

    @Query("SELECT * FROM game_genre_cache WHERE appId = :appId LIMIT 1")
    suspend fun findByAppId(appId: Long): GameGenreCache?

    /**
     * Missing rows come first, then the oldest stale rows, so a new library backfills promptly.
     * Hidden games are excluded: enrichment is a request budget spent on games the player can see
     * (add-hidden-games). Unhiding makes a game eligible again with no extra bookkeeping, because
     * eligibility is this query rather than a stored flag.
     *
     * A row whose `categoriesJson` is null is eligible **regardless of `checkedAt`**, unless the
     * Store recently refused that category lookup (add-gap-plan-suggestions). A null payload with
     * no refusal marker is either an upgraded row that has never been checked for the new data or a
     * refusal whose cooldown has expired. The marker keeps a permanently refused app from consuming
     * every continuation while preserving null as the honest unknown category value.
     * A stale row with a known category payload is held out by the same marker after a refused
     * refresh, so preserving its last-known facts does not cause an immediate retry loop.
     */
    @Query(
        "SELECT games.appId FROM games " +
            "LEFT JOIN game_genre_cache ON games.appId = game_genre_cache.appId " +
            "WHERE games.appId NOT IN (SELECT appId FROM hidden_games) " +
             "AND (game_genre_cache.appId IS NULL " +
             "OR (game_genre_cache.categoriesJson IS NULL " +
             "AND (game_genre_cache.categoriesDeclinedAt IS NULL " +
             "OR game_genre_cache.categoriesDeclinedAt < :staleBefore)) " +
             "OR (game_genre_cache.categoriesJson IS NOT NULL " +
             "AND game_genre_cache.checkedAt < :staleBefore " +
             "AND (game_genre_cache.categoriesDeclinedAt IS NULL " +
             "OR game_genre_cache.categoriesDeclinedAt < :staleBefore))) " +
            "ORDER BY CASE WHEN game_genre_cache.appId IS NULL " +
            "OR game_genre_cache.categoriesJson IS NULL THEN 0 ELSE 1 END, " +
            "game_genre_cache.checkedAt ASC LIMIT :limit",
    )
    suspend fun eligibleAppIds(staleBefore: Long, limit: Int): List<Long>

    @Query(
        "SELECT COUNT(*) FROM games " +
            "LEFT JOIN game_genre_cache ON games.appId = game_genre_cache.appId " +
            "WHERE games.appId NOT IN (SELECT appId FROM hidden_games) " +
             "AND (game_genre_cache.appId IS NULL " +
             "OR (game_genre_cache.categoriesJson IS NULL " +
             "AND (game_genre_cache.categoriesDeclinedAt IS NULL " +
             "OR game_genre_cache.categoriesDeclinedAt < :staleBefore)) " +
             "OR (game_genre_cache.categoriesJson IS NOT NULL " +
             "AND game_genre_cache.checkedAt < :staleBefore " +
             "AND (game_genre_cache.categoriesDeclinedAt IS NULL " +
             "OR game_genre_cache.categoriesDeclinedAt < :staleBefore)))",
    )
    suspend fun eligibleCount(staleBefore: Long): Int

    /**
     * Library items the store reports as something other than a game, hidden ones excluded — the
     * candidates for the non-game bulk review (add-hidden-games).
     *
     * An `INNER JOIN` with `appType IS NOT NULL` is what keeps unknown types out: a game whose
     * type has never been retrieved is neither offered nor assumed to be a game. The comparison is
     * against the normalized lower-case value the data source writes.
     */
    @Query(
        "SELECT games.appId AS appId, games.name AS name, games.iconUrl AS iconUrl, " +
            "game_genre_cache.appType AS appType FROM games " +
            "INNER JOIN game_genre_cache ON games.appId = game_genre_cache.appId " +
            "WHERE game_genre_cache.appType IS NOT NULL AND game_genre_cache.appType <> 'game' " +
            "AND games.appId NOT IN (SELECT appId FROM hidden_games) " +
            "ORDER BY games.name ASC",
    )
    fun observeNonGameCandidates(): Flow<List<NonGameCandidateRow>>
}

/**
 * One non-game library item as the review offer needs it: named, so the player can check it.
 *
 * [appType] is nullable to match the column, even though the query's `WHERE` already excludes
 * nulls — the projection describes the schema rather than restating the filter.
 */
data class NonGameCandidateRow(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val appType: String?,
)
