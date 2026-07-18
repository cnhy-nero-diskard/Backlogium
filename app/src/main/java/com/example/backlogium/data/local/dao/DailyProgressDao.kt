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

    @Query("SELECT * FROM daily_progress WHERE date = :date")
    suspend fun getByDate(date: String): DailyProgress?

    @Query("SELECT * FROM daily_progress ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyProgress>>

    @Query("SELECT * FROM daily_progress ORDER BY date ASC")
    suspend fun getAllOrdered(): List<DailyProgress>
}
