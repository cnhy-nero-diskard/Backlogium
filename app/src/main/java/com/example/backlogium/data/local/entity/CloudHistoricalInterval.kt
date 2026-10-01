package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Historical timing evidence staged independently from Steam-sync-pruned placement evidence. */
@Entity(
    tableName = "cloud_historical_intervals",
    primaryKeys = ["operationId", "appId", "startAt"],
    foreignKeys = [
        ForeignKey(
            entity = CloudHistoricalOperation::class,
            parentColumns = ["operationId", "account", "readerGeneration", "endpointIdentity"],
            childColumns = ["operationId", "account", "readerGeneration", "endpointIdentity"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["operationId", "account", "readerGeneration", "endpointIdentity"])],
)
data class CloudHistoricalInterval(
    val operationId: String,
    val account: String,
    val readerGeneration: Long,
    val endpointIdentity: String,
    val appId: Long,
    val startAt: Long,
    val endAt: Long?,
    val ongoing: Boolean,
    val coverage: String,
    val observedUntil: Long?,
    val coverageLapseFrom: Long?,
    val coverageLapseRecoveredAt: Long?,
    val mayHaveStartedBefore: Boolean,
    val gameName: String?,
    val windowStart: Long,
)
