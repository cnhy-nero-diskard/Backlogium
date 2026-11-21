package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.backlogium.data.local.entity.Game

@Dao
interface DailyActivityDao {
    @Query("SELECT appId, name, iconUrl FROM games WHERE appId NOT IN (SELECT appId FROM hidden_games)")
    suspend fun momentumGames(): List<com.example.backlogium.domain.MomentumGame>

    @Query("SELECT s.appId, s.startAt, s.minutes, s.open FROM sessions s INNER JOIN games g ON g.appId = s.appId " +
        "WHERE s.startAt >= :startInclusive AND s.startAt < :endExclusive AND s.open = 0 AND s.minutes > 0 " +
        "AND s.appId NOT IN (SELECT appId FROM hidden_games)")
    suspend fun momentumRecords(startInclusive: Long, endExclusive: Long): List<com.example.backlogium.domain.MomentumRecord>

    @Query("SELECT MIN(s.startAt) FROM sessions s INNER JOIN games g ON g.appId = s.appId " +
        "WHERE s.open = 0 AND s.minutes > 0 AND s.appId NOT IN (SELECT appId FROM hidden_games)")
    suspend fun earliestMomentumStart(): Long?

    @Query("SELECT * FROM sessions WHERE startAt >= :startInclusive AND startAt < :endExclusive " +
        "AND appId NOT IN (SELECT appId FROM hidden_games) ORDER BY startAt DESC")
    suspend fun readVisibleBetween(startInclusive: Long, endExclusive: Long): List<com.example.backlogium.data.local.entity.Session>

    @Query("SELECT * FROM daily_progress WHERE date >= :start AND date <= :end ORDER BY date DESC")
    suspend fun readProgressBetween(start: String, end: String): List<com.example.backlogium.data.local.entity.DailyProgress>

    @Query("SELECT EXISTS(SELECT 1 FROM daily_progress WHERE date < :date)")
    suspend fun hasProgressBefore(date: String): Boolean

    @Query("SELECT appId, iconUrl, unlockedAt FROM achievements WHERE unlocked = 1 AND retired = 0 " +
        "AND unlockedAt >= :startInclusive AND unlockedAt < :endExclusive " +
        "AND appId NOT IN (SELECT appId FROM hidden_games) ORDER BY unlockedAt ASC")
    suspend fun readUnlocksBetween(startInclusive: Long, endExclusive: Long): List<AchievementUnlock>

    @Query("SELECT * FROM games WHERE appId IN (:appIds)")
    suspend fun readGames(appIds: List<Long>): List<Game>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM games WHERE appId = :appId " +
            "AND appId NOT IN (SELECT appId FROM hidden_games))",
    )
    suspend fun detailAvailable(appId: Long): Boolean
}
