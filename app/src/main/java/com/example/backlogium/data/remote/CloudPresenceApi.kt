package com.example.backlogium.data.remote

import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url

/** Dynamic endpoint client for the authenticated cloud presence reader. */
interface CloudPresenceApi {
    @GET
    suspend fun read(
        @Url endpoint: String,
        @Header("Authorization") authorization: String,
        @Query("position") position: String? = null,
        @Query("mode") mode: String? = null,
        @Query("from") from: String? = null,
        @Query("through") through: String? = null,
    ): CloudPresenceResponseDto
}
