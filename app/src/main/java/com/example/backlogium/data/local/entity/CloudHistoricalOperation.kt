package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable identity and fixed range plus the durable acquisition checkpoint. */
@Entity(
    tableName = "cloud_historical_operations",
    indices = [
        Index(value = ["operationId", "account", "readerGeneration", "endpointIdentity"], unique = true),
        Index(value = ["account", "readerGeneration", "endpointIdentity", "state"]),
    ],
)
data class CloudHistoricalOperation(
    @PrimaryKey val operationId: String,
    val account: String,
    val readerGeneration: Long,
    /** Normalized endpoint URL only; credentials are never copied into Room. */
    val endpointIdentity: String,
    val selectedStartAt: Long,
    val fromAt: Long,
    val throughAt: Long,
    val confirmedCutoffAt: Long? = null,
    val frozenCurrentObservedAt: Long? = null,
    val frozenCurrentAppId: Long? = null,
    val frozenCurrentGameName: String? = null,
    val frozenCurrentPersonastate: Int? = null,
    val frozenCurrentSince: Long? = null,
    val frozenCurrentCoverageLapseFrom: Long? = null,
    val frozenCurrentCoverageLapseRecoveredAt: Long? = null,
    val frozenCurrentSchemaVersion: Int? = null,
    val lastPositionAt: Long? = null,
    val pagesFetched: Int = 0,
    val transitionsFetched: Int = 0,
    val coveredStartAt: Long? = null,
    val coveredEndAt: Long? = null,
    val acquisitionComplete: Boolean = false,
    val state: String = CloudHistoricalStates.ACQUIRING,
    val createdAt: Long,
    val updatedAt: Long,
)

object CloudHistoricalStates {
    const val ACQUIRING = "ACQUIRING"
    const val COMPLETE = "COMPLETE"
    const val APPLYING = "APPLYING"
    const val APPLIED = "APPLIED"
    const val REVERSING = "REVERSING"
    const val REVERSED = "REVERSED"

    const val BOUNDARY_PREDECESSOR = "PREDECESSOR"
    const val BOUNDARY_LAST_TRANSITION = "LAST_TRANSITION"

    const val JOURNAL_PREPARED = "PREPARED"
    const val JOURNAL_APPLY_COMMITTED = "APPLY_COMMITTED"
    const val JOURNAL_APPLIED = "APPLIED"
    const val JOURNAL_REVERSE_COMMITTED = "REVERSE_COMMITTED"
    const val JOURNAL_REVERSED = "REVERSED"
}
