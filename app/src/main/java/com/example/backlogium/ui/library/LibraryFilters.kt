package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.HltbMatchState

/**
 * The transient discovery filters used by Library.
 *
 * Density and section sorting deliberately do not belong here: they are independent display
 * preferences and remain persisted while this value is cleared whenever Library is left.
 */
data class LibraryFilters(
    val query: String = "",
    val selectedGenreIds: Set<String> = emptySet(),
    val notCoveredOnly: Boolean = false,
    val familySharedOnly: Boolean = false,
) {
    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || selectedGenreIds.isNotEmpty() ||
            notCoveredOnly || familySharedOnly

    /** Number of removable chips, with each selected genre represented separately. */
    val activeFilterCount: Int
        get() = (query.isNotBlank()).toInt() + selectedGenreIds.size +
            (notCoveredOnly).toInt() + (familySharedOnly).toInt()

    /** Number of filter categories, used to choose truthful combined-filter recovery copy. */
    val activeCategoryCount: Int
        get() = listOf(
            query.isNotBlank(),
            selectedGenreIds.isNotEmpty(),
            notCoveredOnly,
            familySharedOnly,
        ).count { it }

    fun toggleGenre(id: String): LibraryFilters = copy(
        selectedGenreIds = if (id in selectedGenreIds) selectedGenreIds - id else selectedGenreIds + id,
    )

    fun clearGenre(id: String): LibraryFilters = copy(selectedGenreIds = selectedGenreIds - id)

    fun clearAll(): LibraryFilters = LibraryFilters()

    /** The category responsible for an empty result, or null when no filter is active. */
    fun emptyReason(): LibraryEmptyReason? = when {
        !hasActiveFilters -> null
        activeCategoryCount > 1 -> LibraryEmptyReason.COMBINED
        query.isNotBlank() -> LibraryEmptyReason.QUERY
        selectedGenreIds.isNotEmpty() -> LibraryEmptyReason.GENRES
        notCoveredOnly -> LibraryEmptyReason.NOT_COVERED
        familySharedOnly -> LibraryEmptyReason.FAMILY_SHARED
        else -> null
    }

    /** Every active filter becomes a removable chip, including the query. */
    fun activeChips(availableGenres: Iterable<GameGenre>): List<LibraryFilterChip> {
        val labelsById = availableGenres
            .associateBy(GameGenre::id)
        return buildList {
            if (query.isNotBlank()) {
                add(LibraryFilterChip(LibraryFilterChipKind.QUERY, query.trim()))
            }
            selectedGenreIds
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { labelsById[it]?.label ?: it })
                .forEach { id ->
                    add(
                        LibraryFilterChip(
                            kind = LibraryFilterChipKind.GENRE,
                            value = id,
                            label = labelsById[id]?.label ?: id,
                        ),
                    )
                }
            if (notCoveredOnly) {
                add(LibraryFilterChip(LibraryFilterChipKind.NOT_COVERED))
            }
            if (familySharedOnly) {
                add(LibraryFilterChip(LibraryFilterChipKind.FAMILY_SHARED))
            }
        }
    }
}

enum class LibraryEmptyReason {
    QUERY,
    GENRES,
    NOT_COVERED,
    FAMILY_SHARED,
    COMBINED,
}

enum class LibraryFilterChipKind {
    QUERY,
    GENRE,
    NOT_COVERED,
    FAMILY_SHARED,
}

data class LibraryFilterChip(
    val kind: LibraryFilterChipKind,
    val value: String? = null,
    val label: String? = null,
)

/** Apply the complete immutable filter set in one place, preserving the input order. */
internal fun <T : LibraryRow> List<T>.filterByLibraryFilters(filters: LibraryFilters): List<T> =
    matching(filters.query)
        .filter { game ->
            filters.selectedGenreIds.isEmpty() || game.genres.any { it.id in filters.selectedGenreIds }
        }
        .filter { game -> !filters.notCoveredOnly || game.hltbStatus == HltbMatchState.NOT_COVERED }
        .filter { game -> !filters.familySharedOnly || game.isFamilyShared }

private fun Boolean.toInt(): Int = if (this) 1 else 0
