package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AchievementInsightsDao {
    @Query("SELECT a.appId, g.name AS gameName, a.apiName, a.displayName, a.snapshotPercent AS rarityPercent " +
        "FROM achievements a JOIN games g ON g.appId = a.appId WHERE a.unlocked = 1 AND a.retired = 0 AND a.snapshotPercent IS NOT NULL " +
        "AND a.appId NOT IN (SELECT appId FROM hidden_games) " +
        "AND EXISTS (SELECT 1 FROM player_profile WHERE steamId = :account)")
    suspend fun lifetimeRarity(account: String): List<LifetimeRarityRow>

    @Query("SELECT a.appId, a.apiName, COALESCE(a.displayName, a.apiName) AS name, a.unlockedAt, a.snapshotPercent FROM achievements a " +
        "JOIN game_achievement_sync s ON s.appId = a.appId " +
        "WHERE a.unlocked = 1 AND a.retired = 0 AND s.playerStateFetchedAt IS NOT NULL " +
        "AND a.unlockedAt > 0 AND a.unlockedAt >= :start AND a.unlockedAt < :end " +
        "AND a.appId NOT IN (SELECT appId FROM hidden_games) " +
        "AND EXISTS (SELECT 1 FROM player_profile WHERE steamId = :account)")
    suspend fun unlocks(account: String, start: Long, end: Long): List<DatedUnlockRow>

    @Query("SELECT appId, completedAt FROM game_achievement_sync WHERE completedAt >= :start AND completedAt < :end " +
        "AND appId NOT IN (SELECT appId FROM hidden_games) " +
        "AND EXISTS (SELECT 1 FROM player_profile WHERE steamId = :account)")
    suspend fun completions(account: String, start: Long, end: Long): List<DatedCompletionRow>

    @Query("SELECT COUNT(*) FROM achievements a LEFT JOIN game_achievement_sync s ON s.appId = a.appId " +
        "WHERE a.unlocked = 1 AND a.retired = 0 AND (a.unlockedAt IS NULL OR a.unlockedAt <= 0 OR s.playerStateFetchedAt IS NULL) " +
        "AND a.appId NOT IN (SELECT appId FROM hidden_games) " +
        "AND EXISTS (SELECT 1 FROM player_profile WHERE steamId = :account)")
    suspend fun unsupportedUnlocks(account: String): Int

    @Query("SELECT COUNT(*) FROM game_achievement_sync s WHERE s.completedAt IS NULL AND s.hasAchievements = 1 " +
        "AND EXISTS (SELECT 1 FROM achievements a WHERE a.appId = s.appId AND a.retired = 0) " +
        "AND NOT EXISTS (SELECT 1 FROM achievements a WHERE a.appId = s.appId AND a.retired = 0 AND a.unlocked = 0) " +
        "AND s.appId NOT IN (SELECT appId FROM hidden_games) " +
        "AND EXISTS (SELECT 1 FROM player_profile WHERE steamId = :account)")
    suspend fun unsupportedCompletions(account: String): Int
}

data class DatedUnlockRow(val appId: Long, val apiName: String, val name: String, val unlockedAt: Long, val snapshotPercent: Double?)
data class DatedCompletionRow(val appId: Long, val completedAt: Long)

data class LifetimeRarityRow(val appId: Long, val gameName: String, val apiName: String, val displayName: String?, val rarityPercent: Double)
