package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppData
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StoreCategoryDto
import com.example.backlogium.data.remote.dto.StoreGenreDto
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                categories = emptyList(),
            ),
            result,
        )
    }

    /**
     * The genre half of the three cases is unchanged — a checked negative either way — but the
     * category half is not, and the difference is the point: only the *successful* envelope
     * answered anything about participation.
     */
    @Test fun emptyUnavailableAndMissingEnvelope_areDefinitiveEmpty() = runBlocking {
        val refused = StoreGenreResult.Details(emptyList(), appType = null, categories = null)
        assertEquals(refused, source(Response.success(details(7, false))).genresFor(7))
        assertEquals(refused, source(Response.success(emptyMap())).genresFor(7))
        assertEquals(
            StoreGenreResult.Details(emptyList(), appType = null, categories = emptyList()),
            source(Response.success(details(7, true))).genresFor(7),
        )
    }

    /**
     * A delisted or region-locked game answers `success = false`. Recording "advertises none" for
     * it would classify every such multiplayer game single-player for the next 30 days, on the
     * strength of an answer Steam never gave (add-gap-plan-suggestions).
     */
    @Test fun aRefusedEnvelope_leavesParticipationUnknownRatherThanAdvertisingNone() = runBlocking {
        val refused = source(Response.success(details(7, false))).genresFor(7)
        assertNull((refused as StoreGenreResult.Details).categories)

        // A successful envelope that simply lists no categories *is* an answer, and is empty.
        val answered = source(Response.success(details(7, true))).genresFor(7)
        assertEquals(emptyList<GameCategory>(), (answered as StoreGenreResult.Details).categories)
    }

    @Test fun categoriesAreRetainedSeparatelyFromGenresAndKeepTheirNumericIds() = runBlocking {
        val result = source(
            Response.success(
                details(
                    7, true,
                    genres = listOf(StoreGenreDto("1", "Action")),
                    categories = listOf(
                        StoreCategoryDto(1, "Multi-player"),
                        StoreCategoryDto(38, "Online Co-op"),
                        // Dropped: an entry Steam sent unusably, not one it withheld.
                        StoreCategoryDto(null, "No id"),
                        StoreCategoryDto(20, "  "),
                    ),
                ),
            ),
        ).genresFor(7) as StoreGenreResult.Details

        assertEquals(listOf(GameGenre("1", "Action")), result.genres)
        assertEquals(
            listOf(GameCategory(1, "Multi-player"), GameCategory(38, "Online Co-op")),
            result.categories,
        )
    }

    /**
     * The app type rides along on the response the genre fetch already makes, normalized to lower
     * case so the non-game review's comparison is a plain equality check (add-hidden-games).
     */
    @Test fun appType_isCarriedAndNormalized() = runBlocking {
        assertEquals(
            StoreGenreResult.Details(emptyList(), appType = "application", categories = emptyList()),
            source(Response.success(details(7, true, type = " Application "))).genresFor(7),
        )
        // A blank or absent type stays unknown rather than becoming an empty-string classification.
        assertEquals(
            StoreGenreResult.Details(emptyList(), appType = null, categories = emptyList()),
            source(Response.success(details(7, true, type = "  "))).genresFor(7),
        )
    }

    @Test fun throttlingServerAndNetworkErrors_areTransient() = runBlocking {
        assertTrue(source(Response.error(429, "slow".toResponseBody("text/plain".toMediaType()))).genresFor(7) is StoreGenreResult.TransientFailure)
        assertTrue(source(Response.error(500, "oops".toResponseBody("text/plain".toMediaType()))).genresFor(7) is StoreGenreResult.TransientFailure)
        assertTrue(SteamStoreGenreDataSource(object : SteamStoreApi by NoPrices {
            override suspend fun appDetails(appId: Long, language: String) = throw IOException("offline")
        }).genresFor(7) is StoreGenreResult.TransientFailure)
    }

    private fun details(
        appId: Long,
        success: Boolean,
        genres: List<StoreGenreDto> = emptyList(),
        type: String? = null,
        categories: List<StoreCategoryDto> = emptyList(),
    ) = mapOf(
        appId.toString() to StoreAppDetails(success, StoreAppData(type, genres, categories = categories)),
    )

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
