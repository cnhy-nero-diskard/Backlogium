package com.example.backlogium.ui.home

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionSummary

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

/** Maps collection members into the Home preview order selected by the collection. */
internal fun homeCollectionGamesInDisplayOrder(
    mode: CollectionMode,
    sort: CollectionSort,
    signals: List<CollectionMemberSignals>,
    gamesById: Map<Long, LibraryGame>,
): List<HomeCollectionGame> =
    CollectionSummary.order(mode, sort, signals).map { member ->
        val game = gamesById[member.appId]
        HomeCollectionGame(
            appId = member.appId,
            name = game?.name ?: "Game ${member.appId}",
            iconUrl = game?.iconUrl,
        )
    }

/** Returns true for every collection containing the currently playing app. */
fun homeCollectionContainsPlayingGame(
    games: List<HomeCollectionGame>,
    playingAppId: Long?,
): Boolean = playingAppId != null && games.any { it.appId == playingAppId }

const val HOME_COLLECTION_THUMBNAIL_LIMIT: Int = 3
