package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.backlogium.data.local.entity.CloudHistoricalBoundary
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.CloudHistoricalJournal
import com.example.backlogium.data.local.entity.CloudHistoricalOperation

@Dao
interface CloudHistoricalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(operation: CloudHistoricalOperation): Long

    @Query("SELECT * FROM cloud_historical_operations WHERE operationId = :operationId")
    suspend fun operation(operationId: String): CloudHistoricalOperation?

    @Query(
        "SELECT * FROM cloud_historical_operations " +
            "WHERE account = :account AND readerGeneration = :readerGeneration " +
            "AND endpointIdentity = :endpointIdentity AND state = :state " +
            "ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun operationForIdentity(
        account: String,
        readerGeneration: Long,
        endpointIdentity: String,
        state: String,
    ): CloudHistoricalOperation?

    @Query(
        "UPDATE cloud_historical_operations SET lastPositionAt = :lastPositionAt, " +
            "pagesFetched = :pagesFetched, transitionsFetched = :transitionsFetched, " +
            "coveredStartAt = :coveredStartAt, coveredEndAt = :coveredEndAt, " +
            "acquisitionComplete = :acquisitionComplete, state = :state, updatedAt = :updatedAt " +
            "WHERE operationId = :operationId AND account = :account " +
            "AND readerGeneration = :readerGeneration AND endpointIdentity = :endpointIdentity",
    )
    suspend fun updateProgress(
        operationId: String,
        account: String,
        readerGeneration: Long,
        endpointIdentity: String,
        lastPositionAt: Long?,
        pagesFetched: Int,
        transitionsFetched: Int,
        coveredStartAt: Long?,
        coveredEndAt: Long?,
        acquisitionComplete: Boolean,
        state: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterval(interval: CloudHistoricalInterval)

    @Query(
        "SELECT * FROM cloud_historical_intervals WHERE operationId = :operationId " +
            "AND appId = :appId AND startAt = :startAt",
    )
    suspend fun interval(operationId: String, appId: Long, startAt: Long): CloudHistoricalInterval?

    @Query(
        "SELECT * FROM cloud_historical_intervals WHERE operationId = :operationId " +
            "ORDER BY startAt, appId",
    )
    suspend fun intervals(operationId: String): List<CloudHistoricalInterval>

    /** A stale provisional replay must never replace a closed interval from a later page. */
    @Transaction
    suspend fun upsertInterval(interval: CloudHistoricalInterval) {
        val existing = interval(interval.operationId, interval.appId, interval.startAt)
        if (existing != null && !existing.ongoing && interval.ongoing) return
        insertInterval(interval)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoundary(boundary: CloudHistoricalBoundary)

    @Query(
        "SELECT * FROM cloud_historical_boundaries WHERE operationId = :operationId " +
            "AND kind = :kind",
    )
    suspend fun boundary(operationId: String, kind: String): CloudHistoricalBoundary?

    /** Boundaries are monotonic: a replay of an older page cannot move them backwards. */
    @Transaction
    suspend fun upsertBoundary(boundary: CloudHistoricalBoundary) {
        val existing = boundary(boundary.operationId, boundary.kind)
        if (existing == null || boundary.at >= existing.at) insertBoundary(boundary)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournal(journal: CloudHistoricalJournal)

    @Query("SELECT * FROM cloud_historical_journals WHERE operationId = :operationId")
    suspend fun journal(operationId: String): CloudHistoricalJournal?

    @Query("DELETE FROM cloud_historical_operations")
    suspend fun deleteAllOperations()

    @Query(
        "DELETE FROM cloud_historical_operations WHERE (account != :account " +
            "OR readerGeneration != :readerGeneration OR endpointIdentity != :endpointIdentity) " +
            "AND state IN ('ACQUIRING', 'COMPLETE')",
    )
    suspend fun deleteUnappliedOperationsNotMatching(
        account: String,
        readerGeneration: Long,
        endpointIdentity: String,
    )
}
