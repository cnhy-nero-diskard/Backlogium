package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One app's entry in an **unfiltered** `appdetails` response, where `data` is always an object.
 *
 * Not shared with the price path, which has [StorePriceEnvelope] of its own: a price-filtered
 * request answers `"data": []` for an app with no price, and that array cannot deserialize into
 * [StoreAppData]. Widening this type to tolerate it would add a branch the genre and
 * family-shared-admission callers can never reach, in exchange for coupling two request shapes
 * that have nothing else in common.
 */
@Serializable
data class StoreAppDetails(
    val success: Boolean = false,
    val data: StoreAppData? = null,
)

/**
 * [type] is read by family-shared admission and the hidden-games non-game review: the store is
 * what confirms an unowned app id is a *game* rather than a tool, application, video, or demo —
 * Family Sharing covers a whole library, and admitting a screensaver as a tracked game would erode
 * trust in the whole feature. [name] is read only by family-shared admission. Genre enrichment
 * reads [genres] and [type], ignoring [name].
 */
@Serializable
data class StoreAppData(
    /**
     * The store's own app kind — `game`, `application`, `tool`, `demo`, `music`, … Already present
     * in every `appdetails` response and previously discarded; recording it is what makes the
     * non-game bulk review possible without a single extra request (add-hidden-games).
     */
    val type: String? = null,
    val genres: List<StoreGenreDto> = emptyList(),
    val name: String? = null,
    /**
     * Participation categories — "Multi-player", "Online Co-op", "MMO", … — already present in
     * every `appdetails` response and previously discarded. Retaining them is what makes the
     * gap-plan multiplayer classification possible without a single extra request
     * (add-gap-plan-suggestions).
     */
    val categories: List<StoreCategoryDto> = emptyList(),
)

/** Broad Store genre only; community tags intentionally have no DTO surface. */
@Serializable
data class StoreGenreDto(
    @SerialName("id") val id: String? = null,
    @SerialName("description") val description: String? = null,
)

/**
 * One participation category. **Not a copy of [StoreGenreDto] with a different name**: Steam sends
 * a genre's `id` as a JSON *string* and a category's as a JSON *number*, so sharing one shape
 * would fail to deserialize every response that carries categories.
 *
 * [description] is the localized label. It is retained for display only — classification matches
 * on [id], which does not change with the request's `l=` parameter.
 */
@Serializable
data class StoreCategoryDto(
    @SerialName("id") val id: Int? = null,
    @SerialName("description") val description: String? = null,
)

/**
 * Response envelope for the credential-free Store reviews summary endpoint, `appreviews/{appId}`.
 *
 * [success] is Steam's own `1`/`0` flag, not an HTTP status. A `0` means the Store declined to
 * describe the app id at all — it says nothing about whether the game has reviews, so it must not
 * be cached as "no reviews".
 *
 * Only [querySummary] is retained. The request sends `num_per_page=0`, which suppresses the review
 * bodies while still returning the summary counts — confirmed against a captured response (see
 * `src/test/resources/.../store/appreviews-570.json`), because the summary is all this feature
 * wants and downloading thousands of review texts to discard them would be indefensible.
 */
@Serializable
data class StoreReviewsResponse(
    val success: Int = 0,
    @SerialName("query_summary") val querySummary: StoreReviewSummaryDto? = null,
)

/**
 * [totalReviews] of `0` together with a "No user reviews" [description] is Steam's definitive
 * answer for a game nobody has reviewed — a fact worth caching, and distinct from never having
 * asked.
 */
@Serializable
data class StoreReviewSummaryDto(
    @SerialName("review_score_desc") val description: String? = null,
    @SerialName("total_positive") val totalPositive: Int? = null,
    @SerialName("total_negative") val totalNegative: Int? = null,
    @SerialName("total_reviews") val totalReviews: Int? = null,
)
