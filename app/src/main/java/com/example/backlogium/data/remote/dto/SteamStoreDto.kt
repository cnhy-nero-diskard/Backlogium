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
