package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The verified dataset and its metadata, stored with applied HLTB rows in the same Room
 * transaction. Keeping the payload makes correspondence coverage available independently of any
 * local row whose manual resolution takes precedence.
 */
@Entity(tableName = "hltb_dataset_state")
data class HltbDatasetState(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val schemaVersion: Int,
    val datasetVersion: Long,
    val gatheredAt: Long,
    val payloadJson: String,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
