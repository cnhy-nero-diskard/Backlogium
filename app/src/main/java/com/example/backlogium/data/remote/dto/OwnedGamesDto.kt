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
    /**
     * Null means Steam did not confirm that this is an owned-library response (for example, the
     * empty envelope returned for a private profile). An explicit zero is a valid, successfully
     * tracked empty library and must not be conflated with that response.
     */
    @SerialName("game_count") val gameCount: Int? = null,
    val games: List<OwnedGameDto> = emptyList(),
)

@Serializable
data class OwnedGameDto(
    val appid: Long,
    val name: String = "",
    @SerialName("img_icon_url") val imgIconUrl: String = "",
    /**
     * Steam's cumulative lifetime minutes for an owned game.
     *
     * Valve reports this for every owned game, so an **omitted** value is an anomaly rather than a
     * real reading of zero. Defaulting it to zero would turn "Steam did not say" into "Steam says
     * none", which the diff path then treats as a baseline or a regression. Read it through
     * [validatedPlaytimeForever], which keeps absence and malformed negatives distinct from an
     * explicit `0`.
     */
    @SerialName("playtime_forever") val playtimeForever: Int? = null,
    /**
     * Minutes in the last two weeks. Steam omits this when the game was not played recently, so an
     * absent value *is* zero here rather than unknown; [validatedPlaytime2Weeks] applies that rule
     * and rejects a negative count.
     */
    @SerialName("playtime_2weeks") val playtime2Weeks: Int? = null,
    /**
     * Steam's last-played time in **epoch seconds**, arriving on the same `include_appinfo=1` call
     * the sync already makes. Defaulted so an absent field parses rather than failing the poll:
     * Valve does not contractually document this field, and it is omitted for some games.
     *
     * `0` is Steam's "no value", not 1970 — read this through [lastPlayedAtMillis], which applies
     * both that rule and the unit conversion.
     */
    @SerialName("rtime_last_played") val rtimeLastPlayed: Long = 0,
)

/**
 * [OwnedGameDto.rtimeLastPlayed] as the epoch **millis** the rest of the schema uses, or null when
 * Steam reported no value.
 *
 * The conversion and the zero rule live here, at the payload's boundary, so no consumer can
 * accidentally compare a seconds value against a millis one or record a 1970 last-played date.
 */
val OwnedGameDto.lastPlayedAtMillis: Long?
    get() = rtimeLastPlayed.takeIf { it > 0L }?.times(1_000L)
