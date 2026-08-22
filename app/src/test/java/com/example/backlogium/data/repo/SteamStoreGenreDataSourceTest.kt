package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppData
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StoreGenreDto
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
            StoreGenreResult.Details(
                listOf(GameGenre("1", "Action"), GameGenre("23", "Indie")),
                appType = null,
            ),
            result,
        )
    }

    @Test fun emptyUnavailableAndMissingEnvelope_areDefinitiveEmpty() = runBlocking {
        val empty = StoreGenreResult.Details(emptyList(), appType = null)
        assertEquals(empty, source(Response.success(details(7, false))).genresFor(7))
        assertEquals(empty, source(Response.success(emptyMap())).genresFor(7))
        assertEquals(empty, source(Response.success(details(7, true))).genresFor(7))
    }

    /**
     * The app type rides along on the response the genre fetch already makes, normalized to lower
     * case so the non-game review's comparison is a plain equality check (add-hidden-games).
     */
    @Test fun appType_isCarriedAndNormalized() = runBlocking {
        assertEquals(
            StoreGenreResult.Details(emptyList(), appType = "application"),
            source(Response.success(details(7, true, type = " Application "))).genresFor(7),
        )
        // A blank or absent type stays unknown rather than becoming an empty-string classification.
        assertEquals(
            StoreGenreResult.Details(emptyList(), appType = null),
            source(Response.success(details(7, true, type = "  "))).genresFor(7),
        )
    }

    @Test fun throttlingServerAndNetworkErrors_areTransient() = runBlocking {
        assertTrue(source(Response.error(429, "slow".toResponseBody("text/plain".toMediaType()))).genresFor(7) is StoreGenreResult.TransientFailure)
        assertTrue(source(Response.error(500, "oops".toResponseBody("text/plain".toMediaType()))).genresFor(7) is StoreGenreResult.TransientFailure)
        assertTrue(SteamStoreGenreDataSource(object : SteamStoreApi {
            override suspend fun appDetails(appId: Long, language: String) = throw IOException("offline")
        }).genresFor(7) is StoreGenreResult.TransientFailure)
    }

    private fun details(
        appId: Long,
        success: Boolean,
        genres: List<StoreGenreDto> = emptyList(),
        type: String? = null,
    ) = mapOf(appId.toString() to StoreAppDetails(success, StoreAppData(type, genres)))

    private fun source(response: Response<Map<String, StoreAppDetails>>) =
        SteamStoreGenreDataSource(object : SteamStoreApi {
            override suspend fun appDetails(appId: Long, language: String) = response
        })
}
