package com.example.backlogium.data.updates

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Streaming
import retrofit2.http.Url
import okhttp3.ResponseBody
import retrofit2.Response

interface GitHubReleaseApi {
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/cnhy-nero-diskard/Backlogium/releases/latest")
    suspend fun latestRelease(): GitHubReleaseDto

    /** Streams the optional versioned notes asset so the repository can enforce its own limit. */
    @Streaming
    @GET
    suspend fun structuredNotes(@Url url: String): Response<ResponseBody>
}
