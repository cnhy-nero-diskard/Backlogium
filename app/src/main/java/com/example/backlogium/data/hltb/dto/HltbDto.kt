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
 * Response of the search endpoint's `/init` handshake. Yields the per-request token and the
 * dynamically-named `hpKey`/`hpVal` pair, which the search must echo back both as headers
 * (`x-auth-token`/`x-hp-key`/`x-hp-val`) and as a body field named `hpKey` with value `hpVal`.
 * Resolved at call time and never persisted.
 */
@Serializable
data class HltbInitResponse(
    val token: String? = null,
    val hpKey: String? = null,
    val hpVal: String? = null,
)
