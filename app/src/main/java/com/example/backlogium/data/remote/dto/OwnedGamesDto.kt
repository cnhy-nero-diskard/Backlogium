package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response envelope for `IPlayerService/GetOwnedGames`. */
@Serializable
data class OwnedGamesResponse(
    val response: OwnedGamesResult = OwnedGamesResult(),
)

@Serializable
data class OwnedGamesResult(
    @SerialName("game_count") val gameCount: Int = 0,
    val games: List<OwnedGameDto> = emptyList(),
)

@Serializable
data class OwnedGameDto(
    val appid: Long,
    val name: String = "",
    @SerialName("img_icon_url") val imgIconUrl: String = "",
    @SerialName("playtime_forever") val playtimeForever: Int = 0,
    @SerialName("playtime_2weeks") val playtime2Weeks: Int = 0,
)
