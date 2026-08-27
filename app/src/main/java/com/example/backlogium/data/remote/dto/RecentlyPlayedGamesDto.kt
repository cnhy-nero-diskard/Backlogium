package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response envelope for `IPlayerService/GetRecentlyPlayedGames`.
 *
 * Asked with `count = 1` this is the smallest answer Steam gives to "what did the player just
 * play, and for how long in total" — see add-post-play-sync's design, which chose it over
 * `GetOwnedGames` + `appids_filter` because it is a plain GET (no `input_json` for Retrofit to
 * hand-build and for diagnostics to normalize) and returns one game rather than the library.
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
