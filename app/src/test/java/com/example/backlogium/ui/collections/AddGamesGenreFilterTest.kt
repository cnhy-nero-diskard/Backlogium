package com.example.backlogium.ui.collections

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Test

class AddGamesGenreFilterTest {
    private val action = GameGenre("1", "Action")
    private val indie = GameGenre("23", "Indie")
    private val games = listOf(
        game(1, "Portal", listOf(action)), game(2, "Celeste", listOf(indie)),
        game(3, "Untyped", emptyList()), game(4, "Action Indie", listOf(action, indie)),
    )

    @Test fun filtersExcludeMembersAndUseAdditiveGenreOr() {
        assertEquals(listOf(2L, 3L, 4L), filterAddableGames(games, setOf(1), "", emptySet()).ids())
        assertEquals(listOf(1L, 4L), filterAddableGames(games, emptySet(), "", setOf("1")).ids())
        assertEquals(listOf(1L, 2L, 4L), filterAddableGames(games, emptySet(), "", setOf("1", "23")).ids())
    }

    @Test fun textAndGenreCombineAndUnknownGenresOnlyDropWithActiveGenreFilter() {
        assertEquals(listOf(4L), filterAddableGames(games, emptySet(), "action", setOf("1")).ids())
        assertEquals(listOf(3L), filterAddableGames(games, emptySet(), "untyped", emptySet()).ids())
        assertEquals(emptyList<Long>(), filterAddableGames(games, emptySet(), "untyped", setOf("1")).ids())
    }

    @Test fun catalogIsDeduplicatedAndSorted() {
        assertEquals(listOf("Action", "Indie"), genreFilterCatalog(games).map { it.label })
    }

    private fun List<LibraryGame>.ids() = map { it.appId }
    private fun game(appId: Long, name: String, genres: List<GameGenre>) = LibraryGame(
        appId, name, "", playtimeForever = 0, genres = genres,
    )
}
