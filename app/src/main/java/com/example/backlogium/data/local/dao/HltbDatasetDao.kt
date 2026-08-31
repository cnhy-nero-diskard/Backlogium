package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDatasetLength
import com.example.backlogium.data.local.entity.HltbDatasetMapping
import com.example.backlogium.data.local.entity.HltbDatasetState
import kotlinx.coroutines.flow.Flow

@Dao
interface HltbDatasetDao {
    @Query(
        "SELECT state.schemaVersion, state.datasetVersion, state.gatheredAt, mapping.appId " +
            "FROM hltb_dataset_state AS state " +
            "LEFT JOIN hltb_dataset_mappings AS mapping ON 1 = 1 " +
            "WHERE state.id = 0 ORDER BY mapping.appId",
    )
    fun observeSnapshot(): Flow<List<HltbDatasetSnapshotRow>>

    @Query(
        "SELECT state.schemaVersion, state.datasetVersion, state.gatheredAt, mapping.appId " +
            "FROM hltb_dataset_state AS state " +
            "LEFT JOIN hltb_dataset_mappings AS mapping ON 1 = 1 " +
            "WHERE state.id = 0 ORDER BY mapping.appId",
    )
    suspend fun getSnapshot(): List<HltbDatasetSnapshotRow>

    @Query("SELECT * FROM hltb_dataset_state WHERE id = 0")
    suspend fun getState(): HltbDatasetState?

    @Query(HLTB_DATASET_ROWS_QUERY)
    fun observeAllRows(): Flow<List<HltbData>>

    @Query(HLTB_DATASET_ROWS_QUERY)
    suspend fun getAllRows(): List<HltbData>

    @Query("$HLTB_DATASET_ROWS_QUERY LIMIT 1")
    suspend fun getRow(): HltbData?

    @Query(
        "SELECT mapping.appId AS appId, mapping.hltbId AS hltbId, " +
            "lengths.mainStoryMinutes AS mainStoryMinutes, " +
            "lengths.mainExtraMinutes AS mainExtraMinutes, " +
            "lengths.completionistMinutes AS completionistMinutes, " +
            "lengths.allStylesMinutes AS allStylesMinutes, " +
            "state.gatheredAt AS fetchedAt, 'RESOLVED' AS matchStatus, " +
            "NULL AS candidatesJson, 'DATASET' AS origin " +
            "FROM hltb_dataset_mappings AS mapping " +
            "LEFT JOIN hltb_dataset_lengths AS lengths ON lengths.hltbId = mapping.hltbId " +
            "JOIN hltb_dataset_state AS state ON state.id = 0 " +
            "WHERE mapping.appId = :appId",
    )
    suspend fun getRow(appId: Long): HltbData?

    @Upsert
    suspend fun upsert(state: HltbDatasetState)

    @Upsert
    suspend fun upsertMappings(rows: List<HltbDatasetMapping>)

    @Upsert
    suspend fun upsertLengths(rows: List<HltbDatasetLength>)

    @Query("DELETE FROM hltb_dataset_mappings")
    suspend fun deleteMappings()

    @Query("DELETE FROM hltb_dataset_lengths")
    suspend fun deleteLengths()
}

data class HltbDatasetSnapshotRow(
    val schemaVersion: Int,
    val datasetVersion: Long,
    val gatheredAt: Long,
    val appId: Long?,
)

private const val HLTB_DATASET_ROWS_QUERY =
    "SELECT mapping.appId AS appId, mapping.hltbId AS hltbId, " +
        "lengths.mainStoryMinutes AS mainStoryMinutes, " +
        "lengths.mainExtraMinutes AS mainExtraMinutes, " +
        "lengths.completionistMinutes AS completionistMinutes, " +
        "lengths.allStylesMinutes AS allStylesMinutes, " +
        "state.gatheredAt AS fetchedAt, 'RESOLVED' AS matchStatus, " +
        "NULL AS candidatesJson, 'DATASET' AS origin " +
        "FROM hltb_dataset_mappings AS mapping " +
        "LEFT JOIN hltb_dataset_lengths AS lengths ON lengths.hltbId = mapping.hltbId " +
        "JOIN hltb_dataset_state AS state ON state.id = 0 " +
        "ORDER BY mapping.appId"
