package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The review-summary boundary. Three of these four outcomes are *not* the same thing, and
 * collapsing any pair produces a specific wrong answer:
 *
 * - [Summary] and [Unavailable] are both definitive and both cacheable. "Checked, and nobody has
 *   reviewed this" is a fact; caching it is what stops the enrichment chain asking again every
 *   run for a game that will never have reviews.
 * - [Declined] is the Store refusing to describe the app id — an unsuccessful envelope, as a
 *   delisted or region-locked game produces, or a body that cannot be read at all. Nothing is
 *   written, so the game stays eligible, but the batch continues: one unanswerable app id is not
 *   a reason to stop making progress on the other twenty-four.
 * - [TransientFailure] is a network or HTTP error, including the 429 a throttled client gets. It
 *   ends the batch so WorkManager backs off rather than hammering the Store.
 */
sealed interface StoreReviewResult {
    /**
     * @param description Steam's own `review_score_desc` — the phrase the player already
     *   recognises from a store page, which is why it is retained verbatim rather than re-derived
     *   from the counts.
     */
    data class Summary(
        val description: String,
        val positive: Int,
        val negative: Int,
        val total: Int,
    ) : StoreReviewResult

    /** The Store answered and this game has no usable review data. Cacheable as checked. */
    data object Unavailable : StoreReviewResult

    /** The Store would not answer for this app id. Not an absence; nothing is cached. */
    data object Declined : StoreReviewResult

    data class TransientFailure(val cause: Throwable) : StoreReviewResult
}

@Singleton
class SteamStoreReviewDataSource @Inject constructor(
    private val api: SteamStoreApi,
) {
    /** One `appreviews` request, summary only. Requires no API key and no SteamID. */
    suspend fun reviewsFor(appId: Long): StoreReviewResult {
        return try {
            val response = api.appReviews(appId)
            if (!response.isSuccessful) {
                return StoreReviewResult.TransientFailure(HttpException(response))
            }
            val body = response.body() ?: return StoreReviewResult.Declined
            if (body.success != STEAM_SUCCESS) return StoreReviewResult.Declined
            val summary = body.querySummary ?: return StoreReviewResult.Declined

            val positive = summary.totalPositive ?: 0
            val negative = summary.totalNegative ?: 0
            // Steam's own total, not `positive + negative`: it counts reviews the two buckets do
            // not, and a derived total would quietly disagree with the store page.
            val total = summary.totalReviews ?: 0
            // Zero reviews is the "No user reviews" case — definitive, and cacheable as such. A
            // negative count would be nonsense, and is treated the same way rather than stored.
            if (total <= 0 || positive < 0 || negative < 0) return StoreReviewResult.Unavailable

            val description = summary.description?.trim().orEmpty()
            if (description.isEmpty()) return StoreReviewResult.Unavailable
            StoreReviewResult.Summary(
                description = description,
                positive = positive,
                negative = negative,
                total = total,
            )
        } catch (error: IOException) {
            StoreReviewResult.TransientFailure(error)
        } catch (error: HttpException) {
            StoreReviewResult.TransientFailure(error)
        } catch (_: SerializationException) {
            // A body that will not parse is not an answer about this game, and retrying it forever
            // would never succeed either — so it is declined, like a refused envelope.
            StoreReviewResult.Declined
        }
    }

    private companion object {
        /** Steam's `success` flag in the reviews envelope; anything else is a refusal. */
        const val STEAM_SUCCESS = 1
    }
}
