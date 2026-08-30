package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Every fixture below is a verbatim body recorded from
 * `store.steampowered.com/api/appdetails?...&filters=price_overview`, decoded with the same
 * [Json] configuration [com.example.backlogium.di.NetworkModule] installs. The point is the
 * decode: the `data: []` an app with no price returns is the shape that would throw if this path
 * reused the genre DTO, and it fails the whole batch when it throws, not just its own entry.
 */
class SteamStorePriceDataSourceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test fun paidApp_readsTheRegionsPriceAsSteamFormattedIt() = runBlocking {
        val batch = source(PAID).pricesFor(listOf(292030), "PH")

        assertEquals(
            StorePrice.Amount(
                currency = "PHP",
                finalMinorUnits = 209900,
                initialMinorUnits = 209900,
                discountPercent = 0,
                formatted = "P2,099.00",
                // Steam sends "" here at full price; carrying it would render an empty
                // struck-through price beside a real one.
                listFormatted = null,
            ),
            batch.prices[292030L],
        )
        assertTrue(batch.unresolved.isEmpty())
    }

    @Test fun discountedApp_carriesTheStruckThroughListPrice() = runBlocking {
        val batch = source(DISCOUNTED).pricesFor(listOf(1174180), "PH")

        assertEquals(
            StorePrice.Amount("PHP", 84975, 339900, 75, "P849.75", "P3,399.00"),
            batch.prices[1174180L],
        )
    }

    @Test fun freeApp_isANoPriceAnswer_notAFailure() = runBlocking {
        val batch = source(FREE).pricesFor(listOf(440), "PH")

        assertEquals(StorePrice.None, batch.prices[440L])
        assertTrue(batch.unresolved.isEmpty())
    }

    @Test fun missingNullAndEmptyObjectData_areUnresolved_notNoPrice() = runBlocking {
        val missing = source(MISSING_DATA).pricesFor(listOf(440), "PH")
        val nullData = source(NULL_DATA).pricesFor(listOf(440), "PH")
        val emptyObject = source(EMPTY_OBJECT_DATA).pricesFor(listOf(440), "PH")

        listOf(missing, nullData, emptyObject).forEach { batch ->
            assertTrue(batch.prices.isEmpty())
            assertEquals(setOf(440L), batch.unresolved)
        }
    }

    @Test fun unexpectedDataShape_failsTheChunk_andLeavesItsAppsUnresolved() = runBlocking {
        val batch = source(UNEXPECTED_DATA).pricesFor(listOf(440), "PH")

        assertTrue(batch.prices.isEmpty())
        assertEquals(setOf(440L), batch.unresolved)
    }

    @Test fun mixedBatch_keepsPricedAppsAndSeparatesTheThreeOutcomes() = runBlocking {
        val batch = source(MIXED).pricesFor(listOf(440, 292030, 11), "PH")

        assertEquals(
            mapOf(
                440L to StorePrice.None,
                292030L to StorePrice.Amount("PHP", 209900, 209900, 0, "P2,099.00", null),
            ),
            batch.prices,
        )
        // `success: false` says nothing about whether a price exists, so it must not be recorded
        // as "no price" alongside the free game that genuinely has none.
        assertEquals(setOf(11L), batch.unresolved)
    }

    @Test fun malformedResponse_leavesEveryAppUnresolved() = runBlocking {
        val batch = source(MALFORMED).pricesFor(listOf(440, 292030), "PH")

        assertTrue(batch.prices.isEmpty())
        assertEquals(setOf(440L, 292030L), batch.unresolved)
    }

    @Test fun httpErrorsAndOfflineFailures_resolveNothingAndThrowNothing() = runBlocking {
        val http = SteamStorePriceDataSource(FakeStoreApi(errorResponse = Response.error(
            429, "slow down".toResponseBody("text/plain".toMediaType()),
        )))
        assertEquals(setOf(440L), http.pricesFor(listOf(440), "PH").unresolved)

        val offline = SteamStorePriceDataSource(FakeStoreApi(failure = IOException("offline")))
        assertEquals(setOf(440L), offline.pricesFor(listOf(440), "PH").unresolved)
    }

    @Test fun aFailedChunkFailsOnlyItsOwnAppIds() = runBlocking {
        // 101 ids is two requests. The first answers; the second does not.
        val appIds = (1L..101L).toList()
        val firstChunk = appIds.take(100)
        val api = FakeStoreApi(bodyPerRequest = listOf(
            json.decodeFromString<Map<String, StorePriceEnvelope>>(
                firstChunk.joinToString(",", "{", "}") { """"$it":$PRICED_ENTRY""" },
            ),
            null,
        ))

        val batch = SteamStorePriceDataSource(api).pricesFor(appIds, "PH")

        assertEquals(2, api.requestedIdCsv.size)
        assertEquals(firstChunk.toSet(), batch.prices.keys)
        assertEquals(setOf(101L), batch.unresolved)
    }

    @Test fun anUnknownRegion_isOmittedRatherThanDefaulted() = runBlocking {
        val api = FakeStoreApi(bodyPerRequest = listOf(emptyMap()))

        SteamStorePriceDataSource(api).pricesFor(listOf(440), null)

        assertEquals(listOf<String?>(null), api.requestedCountryCodes)
    }

    private fun source(fixture: String) = SteamStorePriceDataSource(
        FakeStoreApi(bodyPerRequest = listOf(runCatching { json.decodeFromString<Map<String, StorePriceEnvelope>>(fixture) }.getOrNull())),
    )

    private class FakeStoreApi(
        private val bodyPerRequest: List<Map<String, StorePriceEnvelope>?> = emptyList(),
        private val errorResponse: Response<Map<String, StorePriceEnvelope>>? = null,
        private val failure: Throwable? = null,
    ) : SteamStoreApi {
        val requestedIdCsv = mutableListOf<String>()
        val requestedCountryCodes = mutableListOf<String?>()

        override suspend fun appDetails(appId: Long, language: String): Response<Map<String, StoreAppDetails>> =
            error("the price path must not reach the genre call")

        override suspend fun appDetailsPrices(
            appIds: String,
            countryCode: String?,
            filters: String,
        ): Response<Map<String, StorePriceEnvelope>> {
            requestedIdCsv += appIds
            requestedCountryCodes += countryCode
            failure?.let { throw it }
            errorResponse?.let { return it }
            val body = bodyPerRequest.getOrNull(requestedIdCsv.size - 1)
                ?: return Response.error(500, "no body".toResponseBody("text/plain".toMediaType()))
            return Response.success(body)
        }
    }

    private companion object {
        const val PAID = """{"292030":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":209900,"final":209900,"discount_percent":0,"initial_formatted":"","final_formatted":"P2,099.00"}}}}"""

        const val DISCOUNTED = """{"1174180":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":339900,"final":84975,"discount_percent":75,"initial_formatted":"P3,399.00","final_formatted":"P849.75"}}}}"""

        const val FREE = """{"440":{"success":true,"data":[]}}"""

        const val MISSING_DATA = """{"440":{"success":true}}"""

        const val NULL_DATA = """{"440":{"success":true,"data":null}}"""

        const val EMPTY_OBJECT_DATA = """{"440":{"success":true,"data":{}}}"""

        const val UNEXPECTED_DATA = """{"440":{"success":true,"data":"changed"}}"""

        const val MIXED = """{"440":{"success":true,"data":[]},"292030":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":209900,"final":209900,"discount_percent":0,"initial_formatted":"","final_formatted":"P2,099.00"}}},"11":{"success":false}}"""

        /** Truncated mid-document, the shape a withdrawn or rewritten endpoint would produce. */
        const val MALFORMED = """{"440":{"success":true,"data":{"price_overview":"""

        const val PRICED_ENTRY = """{"success":true,"data":{"price_overview":{"currency":"PHP","initial":100,"final":100,"discount_percent":0,"initial_formatted":"","final_formatted":"P1.00"}}}"""
    }
}
