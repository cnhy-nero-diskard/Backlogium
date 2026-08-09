package com.example.backlogium.ui.collections

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Add-games filter is a *view* of the library, never a mutation of the draft collection. The
 * form owns two independent pieces of state — the filter (query + selected genres) and the draft's
 * membership — and this function is the only thing that combines them: it takes membership as an
 * input and returns a list. Nothing here can add, drop, or reorder a member, which is what lets the
 * form keep a filter applied while the user adds game after game through it.
 */
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
        assertEquals(listOf(4L, 1L), filterAddableGames(games, emptySet(), "action", setOf("1")).ids())
        assertEquals(listOf(3L), filterAddableGames(games, emptySet(), "untyped", emptySet()).ids())
        assertEquals(emptyList<Long>(), filterAddableGames(games, emptySet(), "untyped", setOf("1")).ids())
    }

    @Test fun textSearchRanksNameMatchesAboveGenreOnlyMatches() {
        val rankedGames = listOf(
            game(10, "Hundred Hours", listOf(action)),
            game(11, "Red Dead", listOf(action)),
            game(12, "Portal", listOf(GameGenre("99", "Red"))),
        )

        assertEquals(
            listOf(11L, 10L, 12L),
            filterAddableGames(rankedGames, emptySet(), "red", emptySet()).ids(),
        )
    }

    @Test fun memberExclusionHappensBeforeRanking() {
        val rankedGames = listOf(
            game(10, "Red Dead", listOf(action)),
            game(11, "Hundred Hours", listOf(action)),
        )

        assertEquals(
            listOf(11L),
            filterAddableGames(rankedGames, setOf(10L), "red", emptySet()).ids(),
        )
    }

    @Test fun catalogIsDeduplicatedAndSorted() {
        assertEquals(listOf("Action", "Indie"), genreFilterCatalog(games).map { it.label })
    }

    @Test fun noSelectionAtAllOffersTheWholeNonMemberLibrary() {
        // The default state of the form: no query, no genres, nothing added yet.
        assertEquals(listOf(1L, 2L, 3L, 4L), filterAddableGames(games, emptySet(), "", emptySet()).ids())
        // A blank query is not a filter, so surrounding whitespace changes nothing either.
        assertEquals(listOf(1L, 2L, 3L, 4L), filterAddableGames(games, emptySet(), "   ", emptySet()).ids())
    }

    @Test fun aFilterThatMatchesNothingReturnsNothing_notEverything() {
        // The form distinguishes "no results for this filter" from "no games left to add" by
        // comparing this empty list against a non-empty library, so an over-broad fallback here
        // would silently turn a no-match state into the full list.
        assertEquals(emptyList<Long>(), filterAddableGames(games, emptySet(), "skyrim", emptySet()).ids())
        assertEquals(emptyList<Long>(), filterAddableGames(games, emptySet(), "", setOf("99")).ids())
        assertEquals(emptyList<Long>(), filterAddableGames(games, emptySet(), "portal", setOf("23")).ids())
    }

    @Test fun addingGamesOneByOneKeepsTheFilterUsable() {
        val query = "t" // matches all four names, so only the genre selection narrows the list
        val genres = setOf("1", "23")
        val members = mutableSetOf<Long>()

        // Portal, Celeste and Action Indie all match; Untyped is filtered out by the genre selection.
        assertEquals(listOf(1L, 2L, 4L), filterAddableGames(games, members, query, genres).ids())

        // Each add only grows membership. The query and genre selection are untouched, so the same
        // filter keeps narrowing the same result set instead of resetting to the full library.
        members += 4L
        assertEquals(listOf(1L, 2L), filterAddableGames(games, members, query, genres).ids())
        members += 1L
        assertEquals(listOf(2L), filterAddableGames(games, members, query, genres).ids())
        members += 2L
        assertEquals(emptyList<Long>(), filterAddableGames(games, members, query, genres).ids())
    }

    @Test fun clearingFiltersRestoresTheRemainingLibraryWithoutTouchingMembership() {
        val members = setOf(4L)

        // Clear-all is the empty query plus the empty genre set — the same call, no special case.
        val cleared = filterAddableGames(games, members, "", emptySet())

        assertEquals(listOf(1L, 2L, 3L), cleared.ids())
        // The one game already added stays added: clearing a filter is not an "undo add".
        assertEquals(setOf(4L), members)
    }

    @Test fun filteringNeverMutatesTheLibraryOrTheMembershipItWasGiven() {
        val members = setOf(1L, 2L)
        val libraryBefore = games.toList()

        filterAddableGames(games, members, "action", setOf("1", "23"))
        filterAddableGames(games, members, "", emptySet())
        genreFilterCatalog(games)

        assertEquals(libraryBefore, games)
        assertEquals(setOf(1L, 2L), members)
        // The returned rows are the library's own objects, not rebuilt copies that could drift.
        assertTrue(filterAddableGames(games, emptySet(), "portal", emptySet()).size == 1)
        assertSame(games[0], filterAddableGames(games, emptySet(), "portal", emptySet()).single())
    }

    @Test fun aLibraryWithNoGenreDataAtAllOffersNoGenreFilter() {
        // The Genres control is disabled on an empty catalog, and text search still works.
        val untyped = listOf(game(5, "Hades", emptyList()), game(6, "Bastion", emptyList()))

        assertEquals(emptyList<GenreFilterChoice>(), genreFilterCatalog(untyped))
        assertEquals(listOf(5L), filterAddableGames(untyped, emptySet(), "hades", emptySet()).ids())
    }

    private fun List<LibraryGame>.ids() = map { it.appId }
    private fun game(appId: Long, name: String, genres: List<GameGenre>) = LibraryGame(
        appId, name, "", playtimeForever = 0, genres = genres,
    )
}
