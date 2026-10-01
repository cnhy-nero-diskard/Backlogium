package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable apply/undo phase and serialized immutable ledger plan for crash-safe replay. */
@Entity(
    tableName = "cloud_historical_journals",
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
data class CloudHistoricalJournal(
    @PrimaryKey val operationId: String,
    val account: String,
    val readerGeneration: Long,
    val endpointIdentity: String,
    val state: String,
    val payloadVersion: Int = 1,
    /** Versioned JSON payload; its recorded deltas, not live balances, drive replay and undo. */
    val payloadJson: String,
    val updatedAt: Long,
)
