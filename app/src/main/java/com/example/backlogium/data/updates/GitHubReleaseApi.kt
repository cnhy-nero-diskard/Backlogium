package com.example.backlogium.data.updates

import retrofit2.http.GET
import retrofit2.http.Headers

interface GitHubReleaseApi {
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/cnhy-nero-diskard/Backlogium/releases/latest")
    suspend fun latestRelease(): GitHubReleaseDto
}
