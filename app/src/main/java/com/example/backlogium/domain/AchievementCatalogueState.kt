package com.example.backlogium.domain

/** Catalogue knowledge never implies player progress. */
enum class AchievementProgressAvailability { UNKNOWN, CONFIRMED }

data class AchievementCatalogueState(
    val fetchedAt: Long? = null,
    val confirmedEmpty: Boolean = false,
    val progress: AchievementProgressAvailability = AchievementProgressAvailability.UNKNOWN,
)

sealed interface AchievementHydrationState {
    data object Idle : AchievementHydrationState
    data object Loading : AchievementHydrationState
    data object Ready : AchievementHydrationState
    data class Unavailable(val offline: Boolean = false, val retryAt: Long? = null) : AchievementHydrationState
}
