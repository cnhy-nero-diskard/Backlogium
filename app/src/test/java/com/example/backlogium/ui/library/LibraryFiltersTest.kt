package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFiltersTest {
    private val rows = listOf(
        row(1, "Portal", listOf(GameGenre("action", "Action")), HltbMatchState.NOT_COVERED, true),
        row(2, "Celeste", listOf(GameGenre("indie", "Indie")), HltbMatchState.RESOLVED, false),
        row(3, "Hades", listOf(GameGenre("action", "Action")), HltbMatchState.RESOLVED, true),
    )

    @Test
    fun filtersQueryGenreCoverageAndFamilySharedAsOneAndSet() {
        val filters = LibraryFilters(
            query = "por",
            selectedGenreIds = setOf("action"),
            notCoveredOnly = true,
            familySharedOnly = true,
        )

        assertEquals(listOf(1L), rows.filterByLibraryFilters(filters).map { it.appId })
        assertEquals(LibraryEmptyReason.COMBINED, filters.emptyReason())
    }

    @Test
    fun queryAndGenreMatchingRemainCaseInsensitiveAndAnyGenre() {
        val filters = LibraryFilters(query = "IND", selectedGenreIds = setOf("action", "indie"))

        assertEquals(listOf(2L), rows.filterByLibraryFilters(filters).map { it.appId })
    }

    @Test
    fun chipsHaveIndividualGenreRemovalAndClearAll() {
        val filters = LibraryFilters(
            query = "portal",
            selectedGenreIds = setOf("action", "indie"),
            familySharedOnly = true,
        )
        val chips = filters.activeChips(rows.flatMap { it.genres })

        assertEquals(
            listOf(
                LibraryFilterChipKind.QUERY,
                LibraryFilterChipKind.GENRE,
                LibraryFilterChipKind.GENRE,
                LibraryFilterChipKind.FAMILY_SHARED,
            ),
            chips.map(LibraryFilterChip::kind),
        )
        assertEquals(setOf("action"), filters.clearGenre("indie").selectedGenreIds)
        assertFalse(filters.clearAll().hasActiveFilters)
        assertEquals(4, filters.activeFilterCount)
    }

    @Test
    fun emptyReasonNamesEachSingleFilter() {
        assertEquals(LibraryEmptyReason.QUERY, LibraryFilters(query = "missing").emptyReason())
        assertEquals(LibraryEmptyReason.GENRES, LibraryFilters(selectedGenreIds = setOf("rpg")).emptyReason())
        assertEquals(LibraryEmptyReason.NOT_COVERED, LibraryFilters(notCoveredOnly = true).emptyReason())
        assertEquals(LibraryEmptyReason.FAMILY_SHARED, LibraryFilters(familySharedOnly = true).emptyReason())
        assertTrue(LibraryFilters().emptyReason() == null)
    }

    @Test
    fun everyMultipleFilterCombinationUsesRecoverableCombinedReason() {
        val combinations = listOf(
            LibraryFilters(query = "missing", selectedGenreIds = setOf("rpg")),
            LibraryFilters(query = "missing", notCoveredOnly = true),
            LibraryFilters(query = "missing", familySharedOnly = true),
            LibraryFilters(selectedGenreIds = setOf("rpg"), notCoveredOnly = true),
            LibraryFilters(selectedGenreIds = setOf("rpg"), familySharedOnly = true),
            LibraryFilters(notCoveredOnly = true, familySharedOnly = true),
            LibraryFilters(
                query = "missing",
                selectedGenreIds = setOf("rpg"),
                notCoveredOnly = true,
                familySharedOnly = true,
            ),
        )

        combinations.forEach { filters ->
            assertEquals(LibraryEmptyReason.COMBINED, filters.emptyReason())
            assertFalse(filters.clearAll().hasActiveFilters)
        }
    }

    @Test
    fun filtersDoNotOwnOrChangeIndependentSortPreferences() {
        val prefs = LibrarySortPrefs(
            focus = LibrarySortKey.RECENT_ACTIVITY,
            library = LibrarySortKey.XP_CONTRIBUTED,
            focusDirection = LibrarySortDirection.DESCENDING,
            libraryDirection = LibrarySortDirection.ASCENDING,
        )
        val filters = LibraryFilters(selectedGenreIds = setOf("action"), familySharedOnly = true)

        rows.filterByLibraryFilters(filters)

        assertEquals(LibrarySortKey.RECENT_ACTIVITY, prefs.focus)
        assertEquals(LibrarySortKey.XP_CONTRIBUTED, prefs.library)
        assertEquals(LibrarySortDirection.ASCENDING, prefs.libraryDirection)
    }

    @Test
    fun emptyStateUsesAnyActiveFilterAndSelectionModeCanStartEmpty() {
        val state = LibraryUiState(
            filters = LibraryFilters(familySharedOnly = true),
            selectionMode = true,
        )

        assertTrue(state.noMatches)
        assertTrue(state.selectionMode)
        assertTrue(state.selection.isEmpty())
    }

    private fun row(
        appId: Long,
        name: String,
        genres: List<GameGenre>,
        hltbStatus: HltbMatchState,
        isFamilyShared: Boolean,
    ) = TestRow(appId, name, genres, hltbStatus, isFamilyShared)

    private data class TestRow(
        override val appId: Long,
        override val name: String,
        override val genres: List<GameGenre>,
        override val hltbStatus: HltbMatchState,
        override val isFamilyShared: Boolean,
        override val playtimeForever: Int = 0,
        override val playtime2Weeks: Int = 0,
        override val xpContributed: Long = 0L,
    ) : LibraryRow
}
