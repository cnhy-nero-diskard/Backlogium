package com.example.backlogium.data.local.entity

import androidx.room.Entity

/** Acquired owned-game timing evidence; never a credited playtime or a session. */
@Entity(
    tableName = "pending_cloud_intervals",
    primaryKeys = ["account", "generation", "appId", "startAt"],
)
data class PendingCloudInterval(
    val account: String,
    val generation: Long,
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

/** Last opening transition, retained even across a terminal page with no new transitions. */
@Entity(tableName = "pending_cloud_boundaries", primaryKeys = ["account", "generation"])
data class PendingCloudBoundary(
    val account: String,
    val generation: Long,
    val at: Long,
    val appId: Long?,
    val gameName: String?,
    val personastate: Int?,
    val previousLastObservedAt: Long?,
    val previousCoverageLapseFrom: Long?,
    val previousCoverageLapseRecoveredAt: Long?,
    val schemaVersion: Int?,
)
