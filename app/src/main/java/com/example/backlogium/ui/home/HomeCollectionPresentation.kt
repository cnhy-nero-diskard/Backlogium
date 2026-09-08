package com.example.backlogium.ui.home

/** The compact preview contract shared by Home rendering and its JVM arithmetic tests. */
data class HomeCollectionThumbnailPreview(
    val visibleGames: List<HomeCollectionGame>,
    val overflowCount: Int,
)

fun homeCollectionThumbnailPreview(
    games: List<HomeCollectionGame>,
): HomeCollectionThumbnailPreview {
    val visibleGames = games.take(HOME_COLLECTION_THUMBNAIL_LIMIT)
    return HomeCollectionThumbnailPreview(
        visibleGames = visibleGames,
        overflowCount = (games.size - visibleGames.size).coerceAtLeast(0),
    )
}

/** Returns true for every collection containing the currently playing app. */
fun homeCollectionContainsPlayingGame(
    games: List<HomeCollectionGame>,
    playingAppId: Long?,
): Boolean = playingAppId != null && games.any { it.appId == playingAppId }

const val HOME_COLLECTION_THUMBNAIL_LIMIT: Int = 3
