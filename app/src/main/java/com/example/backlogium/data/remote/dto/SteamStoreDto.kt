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
 * [type] and [name] are read only by family-shared admission: the store is what confirms an
 * unowned app id is a *game* rather than a tool, application, video, or demo — Family Sharing
 * covers a whole library, and admitting a screensaver as a tracked game would erode trust in the
 * whole feature. Genre enrichment ignores both.
 */
@Serializable
data class StoreAppData(
    val genres: List<StoreGenreDto> = emptyList(),
    val type: String? = null,
    val name: String? = null,
)

/** Broad Store genre only; categories and community tags intentionally have no DTO surface. */
@Serializable
data class StoreGenreDto(
    @SerialName("id") val id: String? = null,
    @SerialName("description") val description: String? = null,
)
