package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for the verified normalized dataset, stored with its mapping/length relations and
 * applied HLTB rows in the same Room transaction. The JSON payload is deliberately not stored in
 * one TEXT cell: the supported full dataset can exceed Android's per-row CursorWindow capacity.
 */
@Entity(tableName = "hltb_dataset_state")
data class HltbDatasetState(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val schemaVersion: Int,
    val datasetVersion: Long,
    val gatheredAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
