package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.backlogium.data.local.entity.Session
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM sessions WHERE appId = :appId AND open = 1 LIMIT 1")
    suspend fun getOpenSession(appId: Long): Session?

    @Query("SELECT * FROM sessions ORDER BY startAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startAt ASC")
    suspend fun getAll(): List<Session>

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM sessions")
    suspend fun totalTrackedMinutes(): Int
}
