package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One SQLite statement reads all scopes from the same committed snapshot, even for empty days.
 * Room invalidates this query once after a transaction, coalescing the tables' invalidations.
 */
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

    @Query("SELECT MIN(startAt) FROM sessions WHERE appId NOT IN (SELECT appId FROM hidden_games)")
    suspend fun earliestVisibleStart(): Long?

    @Query("SELECT * FROM daily_progress WHERE date >= :start AND date <= :end ORDER BY date DESC")
    suspend fun readProgressBetween(start: String, end: String): List<com.example.backlogium.data.local.entity.DailyProgress>

    @Query("SELECT EXISTS(SELECT 1 FROM daily_progress WHERE date < :date)")
    suspend fun hasProgressBefore(date: String): Boolean

    @Query("SELECT appId, iconUrl, unlockedAt FROM achievements WHERE unlocked = 1 AND retired = 0 " +
        "AND unlockedAt >= :startInclusive AND unlockedAt < :endExclusive " +
        "AND appId NOT IN (SELECT appId FROM hidden_games) ORDER BY unlockedAt ASC")
    suspend fun readUnlocksBetween(startInclusive: Long, endExclusive: Long): List<AchievementUnlock>

    @Query(
        "SELECT s.appId, g.name, s.minutes, g.appId AS detailAppId, " +
            "p.minutesPlayed AS creditedMinutes, p.questMet " +
            "FROM (SELECT 1) anchor " +
            "LEFT JOIN daily_progress p ON p.date = :date " +
            "LEFT JOIN sessions s ON s.startAt >= :startInclusive AND s.startAt < :endExclusive " +
            "AND s.appId NOT IN (SELECT appId FROM hidden_games) " +
            "LEFT JOIN games g ON g.appId = s.appId",
    )
    fun observeDay(date: String, startInclusive: Long, endExclusive: Long): Flow<List<DailyActivityRow>>

    @Query(
        "SELECT s.appId, g.name, s.minutes, g.appId AS detailAppId, " +
            "p.minutesPlayed AS creditedMinutes, p.questMet " +
            "FROM (SELECT 1) anchor " +
            "LEFT JOIN daily_progress p ON p.date = :date " +
            "LEFT JOIN sessions s ON s.startAt >= :startInclusive AND s.startAt < :endExclusive " +
            "AND s.appId NOT IN (SELECT appId FROM hidden_games) " +
            "LEFT JOIN games g ON g.appId = s.appId",
    )
    suspend fun readDay(date: String, startInclusive: Long, endExclusive: Long): List<DailyActivityRow>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM games WHERE appId = :appId " +
            "AND appId NOT IN (SELECT appId FROM hidden_games))",
    )
    suspend fun detailAvailable(appId: Long): Boolean
}

data class DailyActivityRow(
    val appId: Long?,
    val name: String?,
    val minutes: Int?,
    val detailAppId: Long?,
    val creditedMinutes: Long?,
    val questMet: Boolean?,
)
