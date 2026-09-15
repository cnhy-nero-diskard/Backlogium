package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.backlogium.data.local.entity.CloudReadRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudReadDao {
    @Insert
    suspend fun insert(record: CloudReadRecord): Long

    @Query("SELECT * FROM cloud_read_records ORDER BY at DESC")
    fun observeRecords(): Flow<List<CloudReadRecord>>

    @Query(
        "DELETE FROM cloud_read_records " +
            "WHERE id NOT IN (SELECT id FROM cloud_read_records ORDER BY at DESC LIMIT :limit)",
    )
    suspend fun prune(limit: Int)
}
