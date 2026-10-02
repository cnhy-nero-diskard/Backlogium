package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One SQLite statement reads all scopes from the same committed snapshot, even for empty days.
 * Room invalidates this query once after a transaction, coalescing the tables' invalidations.
 */
@Dao
interface DailyActivityDao {
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
