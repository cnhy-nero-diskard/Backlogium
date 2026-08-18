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

    /** Missing rows come first, then the oldest stale rows, so a new library backfills promptly. */
    @Query(
        "SELECT games.appId FROM games " +
            "LEFT JOIN game_genre_cache ON games.appId = game_genre_cache.appId " +
            "WHERE game_genre_cache.appId IS NULL OR game_genre_cache.checkedAt < :staleBefore " +
            "ORDER BY CASE WHEN game_genre_cache.appId IS NULL THEN 0 ELSE 1 END, " +
            "game_genre_cache.checkedAt ASC LIMIT :limit",
    )
    suspend fun eligibleAppIds(staleBefore: Long, limit: Int): List<Long>

    @Query(
        "SELECT COUNT(*) FROM games " +
            "LEFT JOIN game_genre_cache ON games.appId = game_genre_cache.appId " +
            "WHERE game_genre_cache.appId IS NULL OR game_genre_cache.checkedAt < :staleBefore",
    )
    suspend fun eligibleCount(staleBefore: Long): Int
}
