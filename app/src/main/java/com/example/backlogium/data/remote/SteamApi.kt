package com.example.backlogium.data.remote

import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * Steam Web API surface used by the app. The base URL is `https://api.steampowered.com/`.
 * All calls take the API key and SteamID64 as query parameters (supplied by the caller).
 *
 * Every call a diagnostics-tracked worker makes takes an optional [scope] — attached to the
 * underlying `okhttp3.Request` via Retrofit's `@Tag`, and read back by `RedactingTimingInterceptor`
 * so it can credit the request to the run that made it. This exists because `OkHttpClient` and
 * `SteamApi` are singletons shared by every caller: [SteamSyncWorker][com.example.backlogium.work.SteamSyncWorker]
 * and [ReconciliationWorker][com.example.backlogium.work.ReconciliationWorker] can genuinely run
 * concurrently (reconciliation takes minutes and runs on its own schedule), so a request cannot be
 * attributed to "whichever run is currently active" — there can be more than one. Callers outside a
 * tracked run (presence polling, HLTB/Store) simply omit it and the request goes unrecorded rather
 * than being misattributed to an unrelated run.
 */
interface SteamApi {

    @GET("IPlayerService/GetOwnedGames/v1/")
    suspend fun getOwnedGames(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
        @Query("include_appinfo") includeAppInfo: Int = 1,
        @Query("include_played_free_games") includePlayedFreeGames: Int = 1,
        @Tag scope: SyncRunRecorder.RunScope? = null,
    ): OwnedGamesResponse

    /**
     * The player's most recently played games, newest first, with the same cumulative
     * `playtime_forever` [getOwnedGames] reports. Asked with `count = 1` this is the targeted
     * post-play fetch: one small plain-GET response about the game the player just stopped,
     * independent of library size. `count` survives diagnostics redaction as a safe parameter,
     * while `key` and `steamid` do not.
     */
    @GET("IPlayerService/GetRecentlyPlayedGames/v1/")
    suspend fun getRecentlyPlayedGames(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
        @Query("count") count: Int,
        @Tag scope: SyncRunRecorder.RunScope? = null,
    ): RecentlyPlayedGamesResponse

    @GET("IPlayerService/GetSteamLevel/v1/")
    suspend fun getSteamLevel(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
        @Tag scope: SyncRunRecorder.RunScope? = null,
    ): SteamLevelResponse

    /**
     * Current player state, including the running game (`gameid`/`gameextrainfo`) when
     * in-game. The query param is `steamids` (plural, CSV); pass the single configured id.
     */
    @GET("ISteamUser/GetPlayerSummaries/v2/")
    suspend fun getPlayerSummaries(
        @Query("key") key: String,
        @Query("steamids") steamIds: String,
        @Tag scope: SyncRunRecorder.RunScope? = null,
    ): PlayerSummariesResponse

    /** Per-player unlock state for one app's achievements. `success = false` when the profile
     * is private or the app has no stats — see [PlayerAchievementsResult][com.example.backlogium.data.remote.dto.PlayerAchievementsResult]. */
    @GET("ISteamUserStats/GetPlayerAchievements/v1/")
    suspend fun getPlayerAchievements(
        @Query("key") key: String,
        @Query("steamid") steamId: String,
        @Query("appid") appId: Long,
        @Tag scope: SyncRunRecorder.RunScope? = null,
    ): PlayerAchievementsResponse

    /** Global unlock percentage for each of one app's achievements (not per-player). */
    @GET("ISteamUserStats/GetGlobalAchievementPercentagesForApp/v2/")
    suspend fun getGlobalAchievementPercentages(
        @Query("gameid") gameId: Long,
        @Tag scope: SyncRunRecorder.RunScope? = null,
    ): GlobalAchievementPercentagesResponse

    /** Display name + icon per achievement for one app. Games with no achievement schema
     * return an empty result rather than an error. */
    @GET("ISteamUserStats/GetSchemaForGame/v2/")
    suspend fun getSchemaForGame(
        @Query("key") key: String,
        @Query("appid") appId: Long,
        @Tag scope: SyncRunRecorder.RunScope? = null,
    ): GameSchemaResponse

    /**
     * Resolve a vanity profile name (the `<vanity>` in `steamcommunity.com/id/<vanity>`) to a
     * SteamID64. `success = 1` → [steamid][com.example.backlogium.data.remote.dto.ResolveVanityResult.steamId]
     * populated; `success = 42` → no match.
     */
    @GET("ISteamUser/ResolveVanityURL/v1/")
    suspend fun resolveVanityUrl(
        @Query("key") key: String,
        @Query("vanityurl") vanityUrl: String,
    ): ResolveVanityResponse

    /**
     * Current concurrent-player count for one app — a fact about the game, not the player, so
     * unlike every other call on this interface it takes no `key` or steamid. `result != 1`
     * (an invalid or delisted app id) means no player count is returned, rather than zero.
     */
    @GET("ISteamUserStats/GetNumberOfCurrentPlayers/v1/")
    suspend fun getNumberOfCurrentPlayers(
        @Query("appid") appId: Long,
    ): CurrentPlayersResponse
}
