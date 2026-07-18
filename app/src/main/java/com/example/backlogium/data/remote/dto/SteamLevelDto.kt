package com.example.backlogium.data.remote.dto

import kotlinx.serialization.Serializable

/** Response envelope for `IPlayerService/GetSteamLevel`. */
@Serializable
data class SteamLevelResponse(
    val response: SteamLevelResult = SteamLevelResult(),
)

@Serializable
data class SteamLevelResult(
    @kotlinx.serialization.SerialName("player_level") val playerLevel: Int = 0,
)
