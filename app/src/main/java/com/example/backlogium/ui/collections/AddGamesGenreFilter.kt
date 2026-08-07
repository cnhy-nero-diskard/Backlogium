package com.example.backlogium.ui.collections

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.LibraryGame

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
    return games.filter { game ->
        game.appId !in memberAppIds &&
            (trimmed.isEmpty() || game.name.contains(trimmed, ignoreCase = true)) &&
            (selectedGenreIds.isEmpty() || game.genres.any { it.id in selectedGenreIds })
    }
}

/** The filter catalog spans known library metadata and is stable for labels sharing one Store id. */
fun genreFilterCatalog(games: List<LibraryGame>): List<GenreFilterChoice> = games
    .asSequence()
    .flatMap { it.genres.asSequence() }
    .map(GameGenre::toFilterChoice)
    .distinctBy(GenreFilterChoice::id)
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    .toList()

private fun GameGenre.toFilterChoice() = GenreFilterChoice(id, label)
