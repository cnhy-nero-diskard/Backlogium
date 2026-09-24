package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.backlogium.data.local.entity.PendingCloudBoundary
import com.example.backlogium.data.local.entity.PendingCloudInterval

@Dao
interface PendingCloudEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(interval: PendingCloudInterval)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBoundary(boundary: PendingCloudBoundary)

    @Query("SELECT * FROM pending_cloud_boundaries WHERE account = :account AND generation = :generation")
    suspend fun boundary(account: String, generation: Long): PendingCloudBoundary?

    @Query("SELECT * FROM pending_cloud_intervals WHERE account = :account AND generation = :generation ORDER BY startAt")
    suspend fun intervals(account: String, generation: Long): List<PendingCloudInterval>

    @Query("DELETE FROM pending_cloud_intervals WHERE account = :account AND generation = :generation AND appId = :appId AND endAt IS NOT NULL AND ongoing = 0 AND endAt <= :baseline")
    suspend fun pruneClosed(account: String, generation: Long, appId: Long, baseline: Long)

    @Query("DELETE FROM pending_cloud_intervals")
    suspend fun deleteAllIntervals()

    @Query("DELETE FROM pending_cloud_boundaries")
    suspend fun deleteAllBoundaries()

    @Query("DELETE FROM pending_cloud_intervals WHERE account != :account OR generation != :generation")
    suspend fun deleteOtherIntervals(account: String, generation: Long)

    @Query("DELETE FROM pending_cloud_boundaries WHERE account != :account OR generation != :generation")
    suspend fun deleteOtherBoundaries(account: String, generation: Long)
}
