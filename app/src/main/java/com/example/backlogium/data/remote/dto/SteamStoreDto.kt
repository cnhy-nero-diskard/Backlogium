package com.example.backlogium.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StoreAppDetails(
    val success: Boolean = false,
    val data: StoreAppData? = null,
)

@Serializable
data class StoreAppData(
    val genres: List<StoreGenreDto> = emptyList(),
)

/** Broad Store genre only; categories and community tags intentionally have no DTO surface. */
@Serializable
data class StoreGenreDto(
    @SerialName("id") val id: String? = null,
    @SerialName("description") val description: String? = null,
)
