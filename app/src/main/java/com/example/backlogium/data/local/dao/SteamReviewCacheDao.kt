package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.SteamReviewCache
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamReviewCacheDao {

    @Upsert
    suspend fun upsert(cache: SteamReviewCache)

    @Query("DELETE FROM steam_review_cache")
    suspend fun deleteAll()

    @Query("SELECT * FROM steam_review_cache")
    fun observeAll(): Flow<List<SteamReviewCache>>

    /**
     * Missing rows first, then the oldest stale rows — the same missing-before-stale policy
     * [GameGenreCacheDao.eligibleAppIds] applies, so a new library backfills review summaries
     * promptly instead of interleaving them with refreshes of data it already has. A recently
     * declined row is held out until its cooldown expires, so a permanently unanswerable prefix
     * cannot consume every batch's missing-row budget.
     *
     * Hidden games are excluded: enrichment is a request budget spent on games the player can see
     * (add-hidden-games), and unhiding makes a game eligible again with no extra bookkeeping
     * because eligibility is this query rather than a stored flag.
     */
    @Query(
        "SELECT games.appId FROM games " +
            "LEFT JOIN steam_review_cache ON games.appId = steam_review_cache.appId " +
            "WHERE games.appId NOT IN (SELECT appId FROM hidden_games) " +
            "AND (steam_review_cache.appId IS NULL " +
            "OR steam_review_cache.declinedAt < :staleBefore " +
            "OR (steam_review_cache.declinedAt IS NULL " +
            "AND steam_review_cache.checkedAt < :staleBefore)) " +
            "ORDER BY CASE WHEN steam_review_cache.appId IS NULL THEN 0 ELSE 1 END, " +
            "steam_review_cache.checkedAt ASC, games.appId ASC LIMIT :limit",
    )
    suspend fun eligibleAppIds(staleBefore: Long, limit: Int): List<Long>

    @Query(
        "SELECT COUNT(*) FROM games " +
            "LEFT JOIN steam_review_cache ON games.appId = steam_review_cache.appId " +
            "WHERE games.appId NOT IN (SELECT appId FROM hidden_games) " +
            "AND (steam_review_cache.appId IS NULL " +
            "OR steam_review_cache.declinedAt < :staleBefore " +
            "OR (steam_review_cache.declinedAt IS NULL " +
            "AND steam_review_cache.checkedAt < :staleBefore))",
    )
    suspend fun eligibleCount(staleBefore: Long): Int
}
