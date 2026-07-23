package com.example.backlogium.data.hltb

import kotlinx.serialization.Serializable

/**
 * A single HowLongToBeat entry returned for a search, with its four completion lengths (in
 * minutes) and a computed match [confidence] (0.0..1.0, filled by the matcher). Persisted in
 * `candidatesJson` for the review surface, hence [Serializable].
 */
@Serializable
data class HltbCandidate(
    val hltbId: Long,
    val name: String,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val completionistMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val confidence: Double = 0.0,
)

/**
 * The seam that hides HowLongToBeat's transport. The client-side [ScrapingHltbDataSource]
 * implements it today; a server-side proxy could replace it without touching any consumer
 * (goal tagging, batch refresh, review). Consumers depend only on this interface.
 */
interface HltbDataSource {

    /**
     * Search HowLongToBeat for [name] and return its candidate entries (unscored). Throws on
     * network/transport failure so callers can preserve last-good cached data.
     */
    suspend fun search(name: String): List<HltbCandidate>
}
