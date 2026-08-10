package com.example.backlogium.ui.collections

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.ui.search.gameSearchMatchTier

/** A selectable genre choice, de-duplicated by durable Store id. */
data class GenreFilterChoice(val id: String, val label: String)

/**
 * Pure Add-games filtering: membership exclusion, text matching, then additive genre matching.
 * Unknown genre data stays eligible until a genre is specifically selected.
 */
fun filterAddableGames(
    games: List<LibraryGame>,
    memberAppIds: Set<Long>,
    query: String,
    selectedGenreIds: Set<String>,
): List<LibraryGame> {
    val trimmed = query.trim()
    val eligible = games.filter { game ->
        game.appId !in memberAppIds &&
            (selectedGenreIds.isEmpty() || game.genres.any { it.id in selectedGenreIds })
    }
    if (trimmed.isEmpty()) return eligible

    return eligible
        .mapNotNull { game ->
            gameSearchMatchTier(
                query = trimmed,
                name = game.name,
                genreLabels = game.genres.asSequence().map(GameGenre::label).asIterable(),
            )?.let { tier -> game to tier }
        }
        .sortedBy { (_, tier) -> tier.ordinal }
        .map { (game, _) -> game }
}

/** The filter catalog spans known library metadata and is stable for labels sharing one Store id. */
fun genreFilterCatalog(games: List<LibraryGame>): List<GenreFilterChoice> =
    genreFilterCatalog(games.flatMap(LibraryGame::genres))

/** The same catalog logic for Library rows, whose raw games have already become UI models. */
fun genreFilterCatalog(genres: Iterable<GameGenre>): List<GenreFilterChoice> = genres
    .asSequence()
    .map(GameGenre::toFilterChoice)
    .distinctBy(GenreFilterChoice::id)
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    .toList()

private fun GameGenre.toFilterChoice() = GenreFilterChoice(id, label)
