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
    /**
     * Steam's total recent-game count. Like `game_count` on the owned endpoint, this is present
     * for a readable profile and absent from the empty envelope of a private one — so an explicit
     * `total_count: 0` is a confirmed "played nothing recently", whereas an omitted value is
     * unreadable. Confirmation is applied by [isConfirmedRecentEnvelope].
     */
    @SerialName("total_count") val totalCount: Int? = null,
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
    /**
     * Steam's cumulative lifetime minutes. Omitted/malformed values must not be read as zero: see
     * [validatedPlaytimeForever].
     */
    @SerialName("playtime_forever") val playtimeForever: Int? = null,
    /**
     * Minutes in the last two weeks; Steam omits this when the game was not played recently, which
     * [validatedPlaytime2Weeks] reads as zero.
     */
    @SerialName("playtime_2weeks") val playtime2Weeks: Int? = null,
)
