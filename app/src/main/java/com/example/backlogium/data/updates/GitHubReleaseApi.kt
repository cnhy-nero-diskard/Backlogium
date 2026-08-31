package com.example.backlogium.data.updates

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import okhttp3.ResponseBody
import retrofit2.Response

interface GitHubReleaseApi {
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/cnhy-nero-diskard/Backlogium/releases/latest")
    suspend fun latestRelease(): GitHubReleaseDto

    /** Lists releases so independent tag series can select their own newest publication. */
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/cnhy-nero-diskard/Backlogium/releases")
    suspend fun releases(
        @Query("per_page") perPage: Int = RELEASES_PAGE_SIZE,
        @Query("page") page: Int = 1,
    ): List<GitHubReleaseDto>

    /** Streams the optional versioned notes asset so the repository can enforce its own limit. */
    @Streaming
    @GET
    suspend fun structuredNotes(@Url url: String): Response<ResponseBody>
}

/** Reads every GitHub release page so independent tag series cannot hide one another. */
suspend fun GitHubReleaseApi.allReleases(): List<GitHubReleaseDto> {
    val result = mutableListOf<GitHubReleaseDto>()
    var page = 1
    while (true) {
        val releases = releases(perPage = RELEASES_PAGE_SIZE, page = page)
        result += releases
        if (releases.size < RELEASES_PAGE_SIZE) return result
        page++
    }
}

private const val RELEASES_PAGE_SIZE = 100
