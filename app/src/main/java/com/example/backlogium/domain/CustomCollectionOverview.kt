package com.example.backlogium.domain

/**
 * A custom collection as needed by overview surfaces. Membership remains explicit in Room, but
 * callers above data/ only need the ordered app ids and the collection's presentation settings.
 */
data class CustomCollectionOverview(
    val id: Long,
    val name: String,
    val mode: CollectionMode,
    val sort: CollectionSort,
    val targetDate: String?,
    val accent: CollectionAccent?,
    val timeBasis: CollectionTimeBasis,
    val description: String?,
    val displayOrder: Int,
    val memberAppIds: List<Long>,
)
