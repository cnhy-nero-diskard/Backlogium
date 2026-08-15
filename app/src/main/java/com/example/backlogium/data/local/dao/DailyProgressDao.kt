package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.DailyProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyProgressDao {

    @Upsert
    suspend fun upsert(day: DailyProgress)

    /** Ensure the date exists without replacing a concurrent quest result. */
    @Query(
        "INSERT OR IGNORE INTO daily_progress " +
            "(date, minutesPlayed, goalMinutesPlayed, questMet) VALUES (:date, 0, 0, 0)",
    )
    suspend fun ensureDate(date: String)

    /** Add session-start-attributed minutes atomically; never read-add-write a daily total. */
    @Query(
        "UPDATE daily_progress SET minutesPlayed = minutesPlayed + :minutesPlayed, " +
            "goalMinutesPlayed = goalMinutesPlayed + :goalMinutesPlayed WHERE date = :date",
    )
    suspend fun addMinutes(date: String, minutesPlayed: Int, goalMinutesPlayed: Int)

    /** Gamification owns only the derived quest flag; raw playtime remains sync-owned. */
    @Query("UPDATE daily_progress SET questMet = :questMet WHERE date = :date")
    suspend fun updateQuestMet(date: String, questMet: Boolean)

    @Query("SELECT * FROM daily_progress WHERE date = :date")
    suspend fun getByDate(date: String): DailyProgress?

    @Query("SELECT * FROM daily_progress ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyProgress>>

    @Query("SELECT * FROM daily_progress ORDER BY date ASC")
    suspend fun getAllOrdered(): List<DailyProgress>
}
