package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response envelope for `ISteamUser/GetPlayerSummaries`. */
@Serializable
data class PlayerSummariesResponse(
    val response: PlayerSummariesResult = PlayerSummariesResult(),
)

@Serializable
data class PlayerSummariesResult(
    val players: List<PlayerSummaryDto> = emptyList(),
)

/**
 * A single player's summary. [gameId] and [gameExtraInfo] are present only while the
 * player is in-game (and only when the profile is public enough to expose them); Steam
 * serializes `gameid` as a string, so it is kept as a nullable [String] here.
 */
@Serializable
data class PlayerSummaryDto(
    @SerialName("steamid") val steamId: String = "",
    @SerialName("gameid") val gameId: String? = null,
    @SerialName("gameextrainfo") val gameExtraInfo: String? = null,
    @SerialName("personastate") val personaState: Int = 0,
)
