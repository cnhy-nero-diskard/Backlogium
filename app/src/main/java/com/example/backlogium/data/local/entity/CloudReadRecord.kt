package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Bounded cloud-reader audit entry; it contains no credentials or raw presence payload. */
@Entity(tableName = "cloud_read_records", indices = [Index("at")])
data class CloudReadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val trigger: String,
    val outcome: String,
    val windowStart: Long?,
    val windowEnd: Long?,
    val observationCount: Int,
    val nextPosition: String?,
)
