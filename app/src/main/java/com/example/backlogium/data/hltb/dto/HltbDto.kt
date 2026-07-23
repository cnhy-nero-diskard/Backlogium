package com.example.backlogium.data.hltb.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HowLongToBeat search response. The `comp_*` completion lengths are in **seconds**
 * (converted to minutes when mapped to `HltbCandidate`).
 */
@Serializable
data class HltbSearchResponse(
    val data: List<HltbSearchGame> = emptyList(),
)

@Serializable
data class HltbSearchGame(
    @SerialName("game_id") val gameId: Long = 0L,
    @SerialName("game_name") val gameName: String = "",
    @SerialName("comp_main") val compMainSeconds: Int = 0,
    @SerialName("comp_plus") val compPlusSeconds: Int = 0,
    @SerialName("comp_100") val comp100Seconds: Int = 0,
    @SerialName("comp_all") val compAllSeconds: Int = 0,
)

/**
 * Response of the endpoint's `/init` handshake: the per-session token and key/val pair the
 * search POST must echo back. Resolved at call time and never persisted.
 */
@Serializable
data class HltbInitResponse(
    val token: String? = null,
    val key: String? = null,
    @SerialName("val") val value: String? = null,
)

/** Body of the search POST. Fields mirror the shape HowLongToBeat's web client sends. */
@Serializable
data class HltbSearchRequest(
    val searchType: String = "games",
    val searchTerms: List<String>,
    val searchPage: Int = 1,
    val size: Int = 20,
    val searchOptions: HltbSearchOptions = HltbSearchOptions(),
)

@Serializable
data class HltbSearchOptions(
    val games: HltbGamesOptions = HltbGamesOptions(),
    val useCache: Boolean = true,
)

@Serializable
data class HltbGamesOptions(
    val userId: Int = 0,
    val platform: String = "",
    val sortCategory: String = "popular",
)
