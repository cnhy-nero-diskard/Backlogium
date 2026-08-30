package com.example.backlogium.data.repo

import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StoreItemsResponse
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import com.example.backlogium.data.remote.dto.WishlistResponse
import retrofit2.Response

/**
 * A [SteamApi] double for the wishlist paths. Only the two wishlist endpoints answer; every other
 * call is a test failure, so a path that quietly starts spending the player's API key on this
 * feature fails loudly rather than silently.
 */
internal class FakeWishlistApi(
    private val wishlist: suspend () -> WishlistResponse = { error("getWishlist not used") },
    private val storeItems: suspend (String) -> StoreItemsResponse = { StoreItemsResponse() },
) : SteamApi {
    var wishlistCalls = 0
        private set
    var lastStoreItemsInput: String? = null
        private set

    override suspend fun getWishlist(steamId: String, scope: SyncRunRecorder.RunScope?): WishlistResponse {
        wishlistCalls++
        return wishlist()
    }

    override suspend fun getStoreItems(inputJson: String, scope: SyncRunRecorder.RunScope?): StoreItemsResponse {
        lastStoreItemsInput = inputJson
        return storeItems(inputJson)
    }

    override suspend fun getOwnedGames(key: String, steamId: String, includeAppInfo: Int, includePlayedFreeGames: Int, scope: SyncRunRecorder.RunScope?): OwnedGamesResponse = error("not used")
    override suspend fun getRecentlyPlayedGames(key: String, steamId: String, count: Int, scope: SyncRunRecorder.RunScope?): RecentlyPlayedGamesResponse = error("not used")
    override suspend fun getSteamLevel(key: String, steamId: String, scope: SyncRunRecorder.RunScope?): SteamLevelResponse = error("not used")
    override suspend fun getPlayerSummaries(key: String, steamIds: String, scope: SyncRunRecorder.RunScope?): PlayerSummariesResponse = error("not used")
    override suspend fun getPlayerAchievements(key: String, steamId: String, appId: Long, scope: SyncRunRecorder.RunScope?): PlayerAchievementsResponse = error("not used")
    override suspend fun getGlobalAchievementPercentages(gameId: Long, scope: SyncRunRecorder.RunScope?): GlobalAchievementPercentagesResponse = error("not used")
    override suspend fun getSchemaForGame(key: String, appId: Long, scope: SyncRunRecorder.RunScope?): GameSchemaResponse = error("not used")
    override suspend fun resolveVanityUrl(key: String, vanityUrl: String): ResolveVanityResponse = error("not used")
    override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse = error("not used")
}

/** A [SteamStoreApi] double for the price path, recording every chunk it was asked for. */
internal class FakePriceApi(
    private val prices: suspend (String, String?) -> Response<Map<String, StorePriceEnvelope>>,
) : SteamStoreApi {
    val requests = mutableListOf<Pair<String, String?>>()

    override suspend fun appDetails(appId: Long, language: String): Response<Map<String, StoreAppDetails>> =
        error("the wishlist must not reach the genre call")

    override suspend fun appDetailsPrices(
        appIds: String,
        countryCode: String?,
        filters: String,
    ): Response<Map<String, StorePriceEnvelope>> {
        requests += appIds to countryCode
        return prices(appIds, countryCode)
    }
}

/** Credentials as the wishlist needs them: a Steam id, or nothing configured at all. */
internal class FakeCredentials(private val steamId: String?) : CredentialsProvider {
    override suspend fun currentCredentials(): CredentialsState.Configured? =
        steamId?.let { CredentialsState.Configured(apiKey = "key", steamId = it) }
}
