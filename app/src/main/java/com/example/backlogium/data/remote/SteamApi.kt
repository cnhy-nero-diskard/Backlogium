package com.example.backlogium.data.remote

import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Steam Web API surface used by the app. The base URL is `https://api.steampowered.com/`.
 * All calls take the API key and SteamID64 as query parameters (supplied by the caller).
 */
interface SteamApi {

    @GET("IPlayerService/GetOwnedGames/v1/")
    suspend fun getOwnedGames(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
        @Query("include_appinfo") includeAppInfo: Int = 1,
        @Query("include_played_free_games") includePlayedFreeGames: Int = 1,
    ): OwnedGamesResponse

    @GET("IPlayerService/GetSteamLevel/v1/")
    suspend fun getSteamLevel(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
    ): SteamLevelResponse

    /**
     * Current player state, including the running game (`gameid`/`gameextrainfo`) when
     * in-game. The query param is `steamids` (plural, CSV); pass the single configured id.
     */
    @GET("ISteamUser/GetPlayerSummaries/v2/")
    suspend fun getPlayerSummaries(
        @Query("key") key: String,
        @Query("steamids") steamIds: String,
    ): PlayerSummariesResponse
}
