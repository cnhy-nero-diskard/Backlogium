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

/** Move one collection card to an explicit position, preserving every other card's order. */
internal fun <T> homeCollectionOrderAfterMove(
    cards: List<T>,
    fromIndex: Int,
    targetIndex: Int,
): List<T> {
    if (fromIndex !in cards.indices || targetIndex !in cards.indices || fromIndex == targetIndex) {
        return cards
    }
    return cards.toMutableList().apply {
        add(targetIndex, removeAt(fromIndex))
    }
}

const val HOME_COLLECTION_THUMBNAIL_LIMIT: Int = 3
