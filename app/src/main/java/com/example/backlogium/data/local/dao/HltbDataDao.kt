package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.HltbData
import kotlinx.coroutines.flow.Flow

@Dao
interface HltbDataDao {

    @Upsert
    suspend fun upsert(data: HltbData)

    @Query("SELECT * FROM hltb_data WHERE appId = :appId")
    suspend fun getByAppId(appId: Long): HltbData?

    @Query("SELECT * FROM hltb_data")
    fun observeAll(): Flow<List<HltbData>>

    @Query("SELECT * FROM hltb_data")
    suspend fun getAll(): List<HltbData>

    /** Games flagged for manual match review, observed for the review surface. */
    @Query("SELECT * FROM hltb_data WHERE matchStatus = 'NEEDS_REVIEW'")
    fun observeNeedsReview(): Flow<List<HltbData>>

    /**
     * App ids whose HLTB cache is missing or older than [cutoff] (a fetched-at epoch-millis
     * threshold): every game lacking a row, plus every game whose row predates the cutoff.
     * Drives the freshness-gated batch sweep.
     */
    @Query(
        "SELECT appId FROM games WHERE appId NOT IN " +
            "(SELECT appId FROM hltb_data WHERE fetchedAt >= :cutoff)",
    )
    suspend fun appIdsStaleOrMissing(cutoff: Long): List<Long>
}
