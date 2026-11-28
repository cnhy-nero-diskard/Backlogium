package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StoreReviewsResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Captured and synthetic appdetails bodies exercise the production Retrofit decoder and price mapping.
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

    @Test fun unexpectedDataShape_leavesOnlyThatAppUnresolved() = runBlocking {
        val batch = source(UNEXPECTED_DATA).pricesFor(listOf(440, 2), "PH")

        assertEquals(setOf(2L), batch.prices.keys)
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

    @Test fun badFinalFields_doNotDiscardFormattedPricesOrTheirBatchNeighbors() = runBlocking {
        val invalidFinals = listOf(null, "null", "{}", "[]", "true", "\"900\"", "-1", "9.5", "9.0", "9e2", "9223372036854775808")
        val entries = invalidFinals.mapIndexed { index, token ->
            (index + 1L) to priceEntry("""{"currency":" usd ","final_formatted":"  Display price  "${token?.let { ""","final":$it""" }.orEmpty()}}""")
        }.toMap() + (20L to PRICED_ENTRY)

        val batch = source(batchJson(entries)).pricesFor(entries.keys, null)

        assertEquals(entries.keys, batch.prices.keys)
        assertTrue(batch.unresolved.isEmpty())
        for (id in entries.keys - 20L) {
            val amount = batch.prices.getValue(id) as StorePrice.Amount
            assertEquals("USD", amount.currency)
            assertEquals("  Display price  ", amount.formatted)
            assertNull(amount.finalMinorUnits)
            assertFalse(amount.explicitZero)
        }
        assertEquals(100L, (batch.prices.getValue(20L) as StorePrice.Amount).finalMinorUnits)
    }

    @Test fun currencyRequiresARecognizedCode_andZeroRequiresDisplayEvidence() = runBlocking {
        val invalidCurrencies = listOf(null, "null", "[]", "840", "\"\"", "\"   \"", "\"US\"", "\"ZZZ\"", "\"U1D\"")
        val entries = invalidCurrencies.mapIndexed { index, token ->
            (index + 1L) to priceEntry("""{"final":1,"final_formatted":"Display price"${token?.let { ""","currency":$it""" }.orEmpty()}}""")
        }.toMap() + mapOf(
            20L to priceEntry("""{"currency":"  pHp ","final":0,"final_formatted":"P0.00"}"""),
            21L to priceEntry("""{"currency":"PHP","final":9223372036854775807,"final_formatted":"Maximum"}"""),
            22L to priceEntry("""{"currency":"PHP","final":0}"""),
            23L to priceEntry("""{"currency":"PHP","final":0,"final_formatted":null}"""),
            24L to priceEntry("""{"currency":"PHP","final":0,"final_formatted":""}"""),
            25L to priceEntry("""{"currency":"PHP","final":0,"final_formatted":"  "}"""),
            26L to """{"success":true,"data":[]}""",
            27L to priceEntry("[]"),
        )

        val batch = source(batchJson(entries)).pricesFor(entries.keys, null)

        for (id in 1L..invalidCurrencies.size.toLong()) {
            val amount = batch.prices.getValue(id) as StorePrice.Amount
            assertEquals(1L, amount.finalMinorUnits)
            assertNull(amount.currency)
        }
        val zero = batch.prices.getValue(20L) as StorePrice.Amount
        assertEquals("PHP", zero.currency)
        assertEquals(0L, zero.finalMinorUnits)
        assertTrue(zero.explicitZero)
        assertEquals(Long.MAX_VALUE, (batch.prices.getValue(21L) as StorePrice.Amount).finalMinorUnits)
        assertEquals(StorePrice.None, batch.prices.getValue(26L))
        assertEquals(setOf(22L, 23L, 24L, 25L, 27L), batch.unresolved)
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
            json.decodeFromString<JsonObject>(
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
        val api = FakeStoreApi(bodyPerRequest = listOf(JsonObject(emptyMap())))

        SteamStorePriceDataSource(api).pricesFor(listOf(440), null)

        assertEquals(listOf<String?>(null), api.requestedCountryCodes)
    }

    private fun priceEntry(overview: String) =
        """{"success":true,"data":{"price_overview":$overview}}"""

    private fun batchJson(entries: Map<Long, String>) =
        entries.entries.joinToString(",", "{", "}") { (id, entry) -> """"$id":$entry""" }

    private fun source(fixture: String) = SteamStorePriceDataSource(
        storePriceFixtureApi { fixture },
    )

    private class FakeStoreApi(
        private val bodyPerRequest: List<JsonObject?> = emptyList(),
        private val errorResponse: Response<JsonObject>? = null,
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
        ): Response<JsonObject> {
            requestedIdCsv += appIds
            requestedCountryCodes += countryCode
            failure?.let { throw it }
            errorResponse?.let { return it }
            val body = bodyPerRequest.getOrNull(requestedIdCsv.size - 1)
                ?: return Response.error(500, "no body".toResponseBody("text/plain".toMediaType()))
            return Response.success(body)
        }

        /** Review summaries are a separate chain; this double must never be asked for one. */
        override suspend fun appReviews(
            appId: Long,
            json: Int,
            language: String,
            purchaseType: String,
            pageSize: Int,
        ): Response<StoreReviewsResponse> = error("reviews are not part of this test")
    }

    private companion object {
        const val PAID = """{"292030":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":209900,"final":209900,"discount_percent":0,"initial_formatted":"","final_formatted":"P2,099.00"}}}}"""

        const val DISCOUNTED = """{"1174180":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":339900,"final":84975,"discount_percent":75,"initial_formatted":"P3,399.00","final_formatted":"P849.75"}}}}"""

        const val FREE = """{"440":{"success":true,"data":[]}}"""

        const val MISSING_DATA = """{"440":{"success":true}}"""

        const val NULL_DATA = """{"440":{"success":true,"data":null}}"""

        const val EMPTY_OBJECT_DATA = """{"440":{"success":true,"data":{}}}"""

        const val MIXED = """{"440":{"success":true,"data":[]},"292030":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":209900,"final":209900,"discount_percent":0,"initial_formatted":"","final_formatted":"P2,099.00"}}},"11":{"success":false}}"""

        /** Truncated mid-document, the shape a withdrawn or rewritten endpoint would produce. */
        const val MALFORMED = """{"440":{"success":true,"data":{"price_overview":"""

        const val PRICED_ENTRY = """{"success":true,"data":{"price_overview":{"currency":"PHP","initial":100,"final":100,"discount_percent":0,"initial_formatted":"","final_formatted":"P1.00"}}}"""

        const val UNEXPECTED_DATA = """{"440":{"success":true,"data":"changed"},"2":$PRICED_ENTRY}"""
    }
}
