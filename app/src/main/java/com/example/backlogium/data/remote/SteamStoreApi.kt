package com.example.backlogium.data.remote

import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import com.example.backlogium.data.remote.dto.StoreReviewsResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Narrow, credential-free Steam Store endpoints: `appdetails` for genre enrichment, participation
 * categories, and prices, plus `appreviews` for review summaries.
 */
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

    /**
     * All-language review counts for one app, with **no API key and no SteamID** — the summary is
     * public, which is what lets suggestion metadata refresh on an install that has never been
     * given credentials.
     *
     * [pageSize] is `num_per_page`, sent as `0`. That is the parameter that suppresses the review
     * bodies: the response still carries `query_summary` with the positive, negative, and total
     * counts, but its `reviews` array comes back empty. Confirmed against a captured response
     * rather than assumed; raising it would download review prose this feature never reads.
     *
     * [language] is `all` and [purchaseType] is `all` so the counts describe the whole audience
     * rather than one storefront language or one acquisition route — otherwise two games would be
     * ranked against differently-sized populations.
     *
     * Note the path: reviews live at `appreviews/{appId}`, outside the `api/` prefix the other two
     * endpoints share.
     */
    @GET("appreviews/{appId}")
    suspend fun appReviews(
        @Path("appId") appId: Long,
        @Query("json") json: Int = 1,
        @Query("language") language: String = "all",
        @Query("purchase_type") purchaseType: String = "all",
        @Query("num_per_page") pageSize: Int = REVIEW_SUMMARY_PAGE_SIZE,
    ): Response<StoreReviewsResponse>

    companion object {
        const val PRICE_FILTER = "price_overview"

        /** `num_per_page=0`: summary counts only, no review bodies. */
        const val REVIEW_SUMMARY_PAGE_SIZE = 0
    }
}
