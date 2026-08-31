package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One normalized Steam-to-HLTB correspondence from the verified full dataset. */
@Entity(
    tableName = "hltb_dataset_mappings",
    indices = [Index(value = ["hltbId"])],
)
data class HltbDatasetMapping(
    @PrimaryKey val appId: Long,
    val hltbId: Long,
)

/** One normalized completion-length tuple keyed by HLTB id. */
@Entity(tableName = "hltb_dataset_lengths")
data class HltbDatasetLength(
    @PrimaryKey val hltbId: Long,
    val mainStoryMinutes: Int?,
    val mainExtraMinutes: Int?,
    val completionistMinutes: Int?,
    val allStylesMinutes: Int?,
)
