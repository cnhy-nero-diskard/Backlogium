package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** The frozen predecessor and most recent transition needed to reconstruct page overlaps. */
@Entity(
    tableName = "cloud_historical_boundaries",
    primaryKeys = ["operationId", "kind"],
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
data class CloudHistoricalBoundary(
    val operationId: String,
    val account: String,
    val readerGeneration: Long,
    val endpointIdentity: String,
    val kind: String,
    val at: Long,
    val appId: Long?,
    val gameName: String?,
    val personastate: Int?,
    val previousLastObservedAt: Long?,
    val previousCoverageLapseFrom: Long?,
    val previousCoverageLapseRecoveredAt: Long?,
    val schemaVersion: Int?,
)
