package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.backlogium.data.notifications.ProgressAlert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressAlertDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alert: ProgressAlert): Long

    @Query("SELECT * FROM progress_alerts WHERE state = 'PENDING'")
    suspend fun pending(): List<ProgressAlert>

    @Query("SELECT * FROM progress_alerts WHERE state = 'PENDING' OR sharedFallbackPending = 1")
    fun observeWork(): Flow<List<ProgressAlert>>

    @Query("SELECT * FROM progress_alerts WHERE eventKey = :key")
    suspend fun get(key: String): ProgressAlert?

    @Query("UPDATE progress_alerts SET state = :next WHERE eventKey = :key AND state = :expected")
    suspend fun transition(key: String, expected: String, next: String): Int

    @Query("SELECT * FROM progress_alerts WHERE sharedFallbackPending = 1")
    suspend fun sharedFallbacks(): List<ProgressAlert>

    @Query("UPDATE progress_alerts SET sharedFallbackPending = 0 WHERE eventKey = :key AND sharedFallbackPending = 1 AND state != 'PENDING'")
    suspend fun finishSharedFallback(key: String): Int

    @Query("DELETE FROM progress_alerts")
    suspend fun deleteAll()
}
