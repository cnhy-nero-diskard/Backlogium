package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response envelope for `IPlayerService/GetRecentlyPlayedGames`.
 *
 * The post-play fetch asks for a small bounded recent-game window and selects the stopped app from
 * the response. This remains independent of library size while avoiding the assumption that the
 * first row is always the session that just ended.
 */
@Serializable
data class RecentlyPlayedGamesResponse(
    val response: RecentlyPlayedGamesResult = RecentlyPlayedGamesResult(),
)

@Serializable
data class RecentlyPlayedGamesResult(
    @SerialName("total_count") val totalCount: Int = 0,
    val games: List<RecentlyPlayedGameDto> = emptyList(),
)

/**
 * Carries only what session synthesis reads. `img_icon_url` is deliberately absent: this fetch
 * exists to observe playtime, and the commit path preserves a stored name and icon when an
 * observation does not carry them.
 */
@Serializable
data class RecentlyPlayedGameDto(
    val appid: Long,
    val name: String = "",
    @SerialName("playtime_forever") val playtimeForever: Int = 0,
    @SerialName("playtime_2weeks") val playtime2Weeks: Int = 0,
)
