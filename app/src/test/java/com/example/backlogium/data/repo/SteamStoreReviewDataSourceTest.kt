package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import com.example.backlogium.data.remote.dto.StoreReviewSummaryDto
import com.example.backlogium.data.remote.dto.StoreReviewsResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * The six outcomes the enrichment chain has to tell apart. Two of them may be written to the cache
 * and four may not, and the cost of confusing them is concrete: cache a refusal as an absence and
 * a delisted game is permanently "unreviewed"; treat a definitive zero as a failure and the chain
 * re-asks for it every run forever.
 */
class SteamStoreReviewDataSourceTest {

    @Test fun aValidSummaryKeepsSteamsOwnDescriptionAndRawCounts() = runBlocking {
        val result = source(
            Response.success(
                StoreReviewsResponse(
                    success = 1,
                    querySummary = StoreReviewSummaryDto("Very Positive", 2234895, 541071, 2775966),
                ),
            ),
        ).reviewsFor(570)

        assertEquals(StoreReviewResult.Summary("Very Positive", 2234895, 541071, 2775966), result)
    }

    /**
     * The shape is read out of a captured response rather than hand-built, so a field rename on
     * Steam's side surfaces here instead of as silently absent ratings. The same fixture is what
     * confirms `num_per_page=0` suppresses the bodies: `reviews` is empty while the counts remain.
     */
    @Test fun theCapturedResponseParsesThroughTheNarrowDtoWithNoReviewBodies() = runBlocking {
        val body = checkNotNull(
            javaClass.getResourceAsStream(
                "/com/example/backlogium/data/remote/store/appreviews-570.json",
            ),
        ).use { it.readBytes().decodeToString() }
        val json = Json { ignoreUnknownKeys = true }

        val parsed = json.decodeFromString<StoreReviewsResponse>(body)

        assertEquals(1, parsed.success)
        assertEquals("Very Positive", parsed.querySummary?.description)
        assertTrue(checkNotNull(parsed.querySummary?.totalReviews) > 0)
        // The suppressed bodies: the raw response carries an empty `reviews` array.
        assertTrue(body.contains(EMPTY_REVIEWS_ARRAY))

        assertTrue(source(Response.success(parsed)).reviewsFor(570) is StoreReviewResult.Summary)
    }

    /**
     * "No user reviews" is an answer. Caching it is what keeps the chain from re-asking about an
     * obscure game on every single run.
     */
    @Test fun aDefinitiveZeroIsUnavailableRatherThanAFailure() = runBlocking {
        assertEquals(
            StoreReviewResult.Unavailable,
            source(
                Response.success(
                    StoreReviewsResponse(1, StoreReviewSummaryDto("No user reviews", 0, 0, 0)),
                ),
            ).reviewsFor(7),
        )
        // A summary with counts but no description Steam would show is equally unusable.
        assertEquals(
            StoreReviewResult.Unavailable,
            source(Response.success(StoreReviewsResponse(1, StoreReviewSummaryDto("  ", 5, 1, 6))))
                .reviewsFor(7),
        )
    }

    /**
     * A refused envelope says nothing about the game. Writing an absence for it would classify a
     * delisted title as unreviewed for the next 30 days on an answer that was never given.
     */
    @Test fun aRefusedEnvelopeIsDeclinedAndNeverAnAbsence() = runBlocking {
        assertEquals(
            StoreReviewResult.Declined,
            source(Response.success(StoreReviewsResponse(success = 0))).reviewsFor(7),
        )
        assertEquals(
            StoreReviewResult.Declined,
            source(Response.success(StoreReviewsResponse(success = 1, querySummary = null)))
                .reviewsFor(7),
        )
    }

    /** An unreadable body is not an answer either — and retrying it would never become one. */
    @Test fun aMalformedBodyIsDeclined() = runBlocking {
        val source = SteamStoreReviewDataSource(
            failing { throw SerializationException("not json") },
        )

        assertEquals(StoreReviewResult.Declined, source.reviewsFor(7))
    }

    /** HTTP and transport errors end the batch instead, so a throttled client backs off. */
    @Test fun throttlingServerAndNetworkErrors_areTransient() = runBlocking {
        assertTrue(
            source(Response.error(429, "slow".toResponseBody(PLAIN_TEXT.toMediaType())))
                .reviewsFor(7) is StoreReviewResult.TransientFailure,
        )
        assertTrue(
            source(Response.error(500, "oops".toResponseBody(PLAIN_TEXT.toMediaType())))
                .reviewsFor(7) is StoreReviewResult.TransientFailure,
        )
        val offline = SteamStoreReviewDataSource(failing { throw IOException("offline") })
        assertTrue(offline.reviewsFor(7) is StoreReviewResult.TransientFailure)
    }

    /**
     * The endpoint is credential-free by construction: the interface exposes no key or SteamID
     * parameter to pass, and the defaults are the ones the summary contract depends on.
     */
    @Test fun theRequestSendsNoCredentialAndSuppressesReviewBodies() = runBlocking {
        var seen: List<Any?>? = null
        val source = SteamStoreReviewDataSource(object : SteamStoreApi by NoOtherCalls {
            override suspend fun appReviews(
                appId: Long,
                json: Int,
                language: String,
                purchaseType: String,
                pageSize: Int,
            ): Response<StoreReviewsResponse> {
                seen = listOf(appId, json, language, purchaseType, pageSize)
                return Response.success(
                    StoreReviewsResponse(1, StoreReviewSummaryDto("Positive", 9, 1, 10)),
                )
            }
        })

        source.reviewsFor(570)

        assertEquals(listOf<Any?>(570L, 1, "all", "all", 0), seen)
        assertEquals(0, SteamStoreApi.REVIEW_SUMMARY_PAGE_SIZE)
    }

    private fun source(response: Response<StoreReviewsResponse>) =
        SteamStoreReviewDataSource(object : SteamStoreApi by NoOtherCalls {
            override suspend fun appReviews(
                appId: Long,
                json: Int,
                language: String,
                purchaseType: String,
                pageSize: Int,
            ): Response<StoreReviewsResponse> = response
        })

    private fun failing(throwing: () -> Nothing) = object : SteamStoreApi by NoOtherCalls {
        override suspend fun appReviews(
            appId: Long,
            json: Int,
            language: String,
            purchaseType: String,
            pageSize: Int,
        ): Response<StoreReviewsResponse> = throwing()
    }

    /** The review path touches neither appdetails nor prices; delegating asserts that in one place. */
    private object NoOtherCalls : SteamStoreApi {
        override suspend fun appDetails(
            appId: Long,
            language: String,
        ): Response<Map<String, StoreAppDetails>> = error("the review path must not fetch details")

        override suspend fun appDetailsPrices(
            appIds: String,
            countryCode: String?,
            filters: String,
        ): Response<Map<String, StorePriceEnvelope>> = error("the review path must not price anything")

        override suspend fun appReviews(
            appId: Long,
            json: Int,
            language: String,
            purchaseType: String,
            pageSize: Int,
        ): Response<StoreReviewsResponse> = error("not used")
    }

    private companion object {
        const val PLAIN_TEXT = "text/plain"

        /** The literal the captured fixture must still contain for the page-size claim to hold. */
        const val EMPTY_REVIEWS_ARRAY = "\"reviews\":[]"
    }
}
