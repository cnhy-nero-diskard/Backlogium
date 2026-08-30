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
 *
 * [personaName] and [avatarFull] are the player's identity fields, always returned for a
 * public profile. They cost no extra request — this endpoint is already polled — and are
 * persisted onto the profile so the header renders offline.
 *
 * [locCountryCode] is the public/community profile location. It is not the account's Steam Store
 * Country, which is a separate payment-derived setting, so callers must not use it to select a
 * store region or currency.
 */
@Serializable
data class PlayerSummaryDto(
    @SerialName("steamid") val steamId: String = "",
    @SerialName("gameid") val gameId: String? = null,
    @SerialName("gameextrainfo") val gameExtraInfo: String? = null,
    @SerialName("personastate") val personaState: Int = 0,
    @SerialName("personaname") val personaName: String = "",
    @SerialName("avatarfull") val avatarFull: String? = null,
    @SerialName("loccountrycode") val locCountryCode: String? = null,
)
