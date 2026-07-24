package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response envelope for `ISteamUser/ResolveVanityURL`. */
@Serializable
data class ResolveVanityResponse(
    val response: ResolveVanityResult = ResolveVanityResult(),
)

/**
 * Result of resolving a vanity name. [success] is `1` when a profile matched (with [steamId]
 * populated) and `42` when no profile was found; other values indicate an API-side error.
 */
@Serializable
data class ResolveVanityResult(
    val success: Int = 0,
    @SerialName("steamid") val steamId: String? = null,
)
