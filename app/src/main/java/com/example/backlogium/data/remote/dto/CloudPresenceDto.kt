package com.example.backlogium.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CloudPresenceResponseDto(
    val account: String? = null,
    val transitions: List<CloudPresenceTransitionDto> = emptyList(),
    val current: CloudPresenceCurrentDto? = null,
    val nextPosition: String? = null,
    val hasMore: Boolean = false,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val readAt: String? = null,
)

@Serializable
data class CloudPresenceTransitionDto(
    val v: Int? = null,
    val t: String? = null,
    val prevLastObservedAt: String? = null,
    val prevCoverageLapseFrom: String? = null,
    val prevCoverageLapseRecoveredAt: String? = null,
    val personastate: Int? = null,
    val gameid: String? = null,
    val gameName: String? = null,
)

@Serializable
data class CloudPresenceCurrentDto(
    val v: Int? = null,
    val lastObservedAt: String? = null,
    val coverageLapseFrom: String? = null,
    val coverageLapseRecoveredAt: String? = null,
    val since: String? = null,
    val updatedAt: String? = null,
    val personastate: Int? = null,
    val gameid: String? = null,
    val gameName: String? = null,
)
