package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.HltbDatasetState
import kotlinx.coroutines.flow.Flow

@Dao
interface HltbDatasetDao {
    @Query("SELECT * FROM hltb_dataset_state WHERE id = 0")
    fun observeState(): Flow<HltbDatasetState?>

    @Query("SELECT * FROM hltb_dataset_state WHERE id = 0")
    suspend fun getState(): HltbDatasetState?

    @Upsert
    suspend fun upsert(state: HltbDatasetState)
}
