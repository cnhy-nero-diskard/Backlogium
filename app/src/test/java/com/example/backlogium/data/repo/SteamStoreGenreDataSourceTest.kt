package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppData
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StoreGenreDto
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class SteamStoreGenreDataSourceTest {

    @Test fun orderedGenres_ignoreMalformedEntries() = runBlocking {
        val result = source(Response.success(details(7, true, listOf(
            StoreGenreDto("1", "Action"), StoreGenreDto("", "Bad"), StoreGenreDto("23", "Indie"),
        )))).genresFor(7)

        assertEquals(
            StoreGenreResult.Genres(listOf(GameGenre("1", "Action"), GameGenre("23", "Indie"))),
            result,
        )
    }

    @Test fun emptyUnavailableAndMissingEnvelope_areDefinitiveEmpty() = runBlocking {
        assertEquals(StoreGenreResult.Empty, source(Response.success(details(7, false))).genresFor(7))
        assertEquals(StoreGenreResult.Empty, source(Response.success(emptyMap())).genresFor(7))
        assertEquals(StoreGenreResult.Empty, source(Response.success(details(7, true))).genresFor(7))
    }

    @Test fun throttlingServerAndNetworkErrors_areTransient() = runBlocking {
        assertTrue(source(Response.error(429, "slow".toResponseBody("text/plain".toMediaType()))).genresFor(7) is StoreGenreResult.TransientFailure)
        assertTrue(source(Response.error(500, "oops".toResponseBody("text/plain".toMediaType()))).genresFor(7) is StoreGenreResult.TransientFailure)
        assertTrue(SteamStoreGenreDataSource(object : SteamStoreApi by NoPrices {
            override suspend fun appDetails(appId: Long, language: String) = throw IOException("offline")
        }).genresFor(7) is StoreGenreResult.TransientFailure)
    }

    private fun details(appId: Long, success: Boolean, genres: List<StoreGenreDto> = emptyList()) =
        mapOf(appId.toString() to StoreAppDetails(success, StoreAppData(genres)))

    private fun source(response: Response<Map<String, StoreAppDetails>>) =
        SteamStoreGenreDataSource(object : SteamStoreApi by NoPrices {
            override suspend fun appDetails(appId: Long, language: String) = response
        })

    /** The genre path never prices anything; delegating keeps that assertion in one place. */
    private object NoPrices : SteamStoreApi {
        override suspend fun appDetails(appId: Long, language: String): Response<Map<String, StoreAppDetails>> =
            error("not used")

        override suspend fun appDetailsPrices(
            appIds: String,
            countryCode: String?,
            filters: String,
        ): Response<Map<String, StorePriceEnvelope>> = error("the genre path must not price anything")
    }
}
