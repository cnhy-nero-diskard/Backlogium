package com.example.backlogium.ui.home

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.domain.CollectionMode

/** The small game projection needed by Home's next-action surface. */
data class HomeNextGame(
    val appId: Long,
    val name: String?,
    val iconUrl: String? = null,
)

/** One deterministic action Home can offer without a recommendation service. */
sealed interface HomeNextAction {
    data class ContinueFocus(val game: HomeNextGame) : HomeNextAction

    data class ContinueCollection(
        val collectionId: Long,
        val collectionName: String,
        val game: HomeNextGame,
    ) : HomeNextAction

    data object ChooseGame : HomeNextAction
}

/**
 * Select the one next action Home should surface from local presentation inputs.
 *
 * The input order is intentionally not used to decide the Focus winner: the most recent Steam
 * play wins, with app id as a deterministic tie-breaker. Collection order is already the user's
 * persisted display order and is therefore retained exactly. An unknown HLTB length is not proof
 * of completion, so that Focus game remains eligible until it can be classified otherwise.
 */
internal fun selectHomeNextAction(
    focusGames: List<LibraryGame>,
    collections: List<HomeCollectionCard>,
    currentlyPlayingAppId: Long? = null,
): HomeNextAction {
    val focusGame = focusGames
        .asSequence()
        .filter { it.isGoal && it.isIncompleteFocusGame() }
        .filterNot { it.appId == currentlyPlayingAppId }
        .sortedWith(
            compareByDescending<LibraryGame> { it.lastPlayedAt ?: Long.MIN_VALUE }
                .thenBy { it.appId },
        )
        .firstOrNull()

    if (focusGame != null) {
        return HomeNextAction.ContinueFocus(focusGame.toHomeNextGame())
    }

    val collectionAction = collections.asSequence()
        .filter { it.mode == CollectionMode.ORDERED_QUEUE }
        .mapNotNull { card ->
            val nextUp = card.banner.nextUp ?: return@mapNotNull null
            if (nextUp.appId == currentlyPlayingAppId) return@mapNotNull null
            val game = card.games.firstOrNull { it.appId == nextUp.appId }
                ?: HomeCollectionGame(
                    appId = nextUp.appId,
                    name = nextUp.name,
                    iconUrl = null,
                )
            HomeNextAction.ContinueCollection(
                collectionId = card.collectionId,
                collectionName = card.name,
                game = game.toHomeNextGame(),
            )
        }
        .firstOrNull()

    return collectionAction ?: HomeNextAction.ChooseGame
}

/** Adapter used by the ViewModel combine and by state-transition tests. */
internal fun nextActionForHomeState(
    focusGames: List<LibraryGame>,
    collections: List<HomeCollectionCard>,
    nowPlaying: NowPlaying,
): HomeNextAction = selectHomeNextAction(
    focusGames = focusGames,
    collections = collections,
    currentlyPlayingAppId = (nowPlaying as? NowPlaying.InGame)?.gameId,
)

private fun LibraryGame.isIncompleteFocusGame(): Boolean =
    completionistMinutes?.takeIf { it > 0 }?.let { playtimeForever < it } ?: true

private fun LibraryGame.toHomeNextGame() = HomeNextGame(
    appId = appId,
    name = name,
    iconUrl = iconUrl.takeIf { it.isNotBlank() },
)

private fun HomeCollectionGame.toHomeNextGame() = HomeNextGame(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
)
