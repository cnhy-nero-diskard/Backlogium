package com.example.backlogium.data.remote

import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Narrow, credential-free Steam Store `appdetails` endpoint: genre enrichment and prices. */
interface SteamStoreApi {
    @GET("api/appdetails")
    suspend fun appDetails(
        @Query("appids") appId: Long,
        @Query("l") language: String = "english",
    ): Response<Map<String, StoreAppDetails>>

    /**
     * Prices for many apps in one request, keyed by app id as strings.
     *
     * [appIds] is a comma-separated list. `filters=price_overview` is what makes the endpoint
     * answer for a whole list at once — the same request with `filters=basic`, or with no filter,
     * returns a bare `null` for anything past a single id — so this call cannot be widened to
     * carry names along with the prices.
     *
     * [countryCode] is the player's store region. It is **nullable and omitted when null**
     * (Retrofit drops a null query parameter), because Steam then resolves a region from the
     * request itself, which is a better answer than a hardcoded default confidently pricing in
     * the wrong currency.
     */
    @GET("api/appdetails")
    suspend fun appDetailsPrices(
        @Query("appids") appIds: String,
        @Query("cc") countryCode: String?,
        @Query("filters") filters: String = PRICE_FILTER,
    ): Response<Map<String, StorePriceEnvelope>>

    companion object {
        const val PRICE_FILTER = "price_overview"
    }
}
