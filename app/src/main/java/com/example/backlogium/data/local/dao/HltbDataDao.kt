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

    @Upsert
    suspend fun upsertAll(data: List<HltbData>)

    @Query("DELETE FROM hltb_data WHERE origin = 'DATASET'")
    suspend fun deleteDatasetRows()

    @Query("SELECT * FROM hltb_data WHERE appId = :appId")
    suspend fun getByAppId(appId: Long): HltbData?

    @Query("SELECT * FROM hltb_data")
    fun observeAll(): Flow<List<HltbData>>

    @Query("SELECT * FROM hltb_data")
    suspend fun getAll(): List<HltbData>

    /** One Room query over cache and normalized dataset tables, so every emission is one snapshot. */
    @Query(HLTB_DATA_WITH_DATASET_QUERY)
    fun observeAllWithDataset(): Flow<List<HltbData>>

    /** Cache-over-dataset precedence read from one SQLite snapshot. */
    @Query(HLTB_DATA_WITH_DATASET_QUERY)
    suspend fun getAllWithDataset(): List<HltbData>

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

private const val HLTB_DATA_WITH_DATASET_QUERY =
    "SELECT appId, hltbId, mainStoryMinutes, mainExtraMinutes, completionistMinutes, " +
        "allStylesMinutes, fetchedAt, matchStatus, candidatesJson, origin FROM hltb_data " +
        "UNION ALL " +
        "SELECT mapping.appId AS appId, mapping.hltbId AS hltbId, " +
        "lengths.mainStoryMinutes AS mainStoryMinutes, " +
        "lengths.mainExtraMinutes AS mainExtraMinutes, " +
        "lengths.completionistMinutes AS completionistMinutes, " +
        "lengths.allStylesMinutes AS allStylesMinutes, state.gatheredAt AS fetchedAt, " +
        "'RESOLVED' AS matchStatus, NULL AS candidatesJson, 'DATASET' AS origin " +
        "FROM hltb_dataset_mappings AS mapping " +
        "LEFT JOIN hltb_dataset_lengths AS lengths ON lengths.hltbId = mapping.hltbId " +
        "JOIN hltb_dataset_state AS state ON state.id = 0 " +
        "WHERE NOT EXISTS (SELECT 1 FROM hltb_data AS cached WHERE cached.appId = mapping.appId) " +
        "ORDER BY appId"
