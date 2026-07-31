package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response envelope for `ISteamUserStats/GetNumberOfCurrentPlayers`. */
@Serializable
data class CurrentPlayersResponse(
    val response: CurrentPlayersResult = CurrentPlayersResult(),
)

/**
 * [playerCount] is present only when [result] is the success code (`1`); an invalid or
 * delisted app id comes back with [result] set to something else and no [playerCount] at all,
 * rather than a zero.
 */
@Serializable
data class CurrentPlayersResult(
    @SerialName("player_count") val playerCount: Int? = null,
    val result: Int = 0,
)
