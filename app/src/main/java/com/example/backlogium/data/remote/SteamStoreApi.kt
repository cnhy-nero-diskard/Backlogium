package com.example.backlogium.data.remote

import com.example.backlogium.data.remote.dto.StoreAppDetails
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Narrow, credential-free Steam Store `appdetails` endpoint used only for genre enrichment. */
interface SteamStoreApi {
    @GET("api/appdetails")
    suspend fun appDetails(
        @Query("appids") appId: Long,
        @Query("l") language: String = "english",
    ): Response<Map<String, StoreAppDetails>>
}
