package com.example.backlogium.ui.library

import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.librarySortKeyOrNull
import org.junit.Assert.*
import org.junit.Test

class LibraryAddedRecentlyTest {
    private fun game(id: Long, name: String, time: Long?) = BacklogGameUi(
        appId = id, name = name, iconUrl = "", playtimeForever = 0, firstSeenAt = time,
    )
    private val games = listOf(
        game(1, "Legacy", null), game(2, "New", 3_000), game(3, "Old", 1_000),
        game(4, "Batch zeta", 2_000), game(6, "Batch alpha", 2_000), game(5, "BATCH ALPHA", 2_000),
        game(7, "Baseline", null),
    )

    @Test fun newestFirstKeepsBaselineAndLegacyLastWithNormalizedTitleAndIdTies() {
        assertEquals(listOf(2L, 5L, 6L, 4L, 3L, 7L, 1L), games.sortedFor(LibrarySortKey.ADDED_RECENTLY).map { it.appId })
        assertNull(games.first().firstSeenAt)
        assertNull(games.last().firstSeenAt)
        assertEquals(LibrarySortDirection.DESCENDING, LibrarySortKey.ADDED_RECENTLY.defaultDirection)
    }

    @Test fun oldestFirstStillKeepsUnknownLastAndDoesNotReverseBatchTies() {
        assertEquals(listOf(3L, 5L, 6L, 4L, 2L, 7L, 1L),
            games.sortedFor(LibrarySortKey.ADDED_RECENTLY, LibrarySortDirection.ASCENDING).map { it.appId })
    }

    @Test fun allUndatedRowsStayStableInBothDirections() {
        val undated = listOf(game(3, "Zeta", null), game(2, "alpha", null), game(1, "Alpha", null))
        for (direction in LibrarySortDirection.entries) {
            assertEquals(listOf(1L, 2L, 3L), undated.sortedFor(LibrarySortKey.ADDED_RECENTLY, direction).map { it.appId })
        }
    }

    @Test fun relevanceLeadsAddedDateEvenForUnknownExactMatchAndReversedDirection() {
        val searched = listOf(game(1, "Portal", null), game(2, "Portal newer", 2_000), game(3, "Portal older", 1_000))
        assertEquals(listOf(1L, 2L, 3L), searched.sortedFor(LibrarySortKey.ADDED_RECENTLY, query = "portal").map { it.appId })
        assertEquals(listOf(1L, 3L, 2L), searched.sortedFor(
            LibrarySortKey.ADDED_RECENTLY, LibrarySortDirection.ASCENDING, "portal",
        ).map { it.appId })
    }

    @Test fun oldPersistedNamesAndDefaultsAreUnchangedAndNewNameParses() {
        for (key in listOf(LibrarySortKey.NAME, LibrarySortKey.PLAYTIME, LibrarySortKey.RECENT_ACTIVITY, LibrarySortKey.XP_CONTRIBUTED)) {
            assertEquals(key, librarySortKeyOrNull(key.name))
        }
        assertEquals(LibrarySortKey.ADDED_RECENTLY, librarySortKeyOrNull("ADDED_RECENTLY"))
        assertNull(librarySortKeyOrNull("unknown"))
        assertEquals(LibrarySortKey.NAME, LibrarySortPrefs().focus)
        assertEquals(LibrarySortKey.PLAYTIME, LibrarySortPrefs().library)
        assertEquals("newest first", librarySortDirectionLabel(LibrarySortKey.ADDED_RECENTLY, LibrarySortDirection.DESCENDING))
        assertEquals("oldest first", librarySortDirectionLabel(LibrarySortKey.ADDED_RECENTLY, LibrarySortDirection.ASCENDING))
    }

    @Test fun bothScreenProjectionsCarryRecordedTimeWithoutFillingUnknowns() {
        val xp = XpInputs(emptyMap(), emptyMap(), com.example.backlogium.gamification.RuleConfig())
        for (time in listOf(null, 123_456L)) {
            val source = com.example.backlogium.data.repo.LibraryGame(
                appId = 1, name = "Arrival", iconUrl = "", playtimeForever = 0, firstSeenAt = time,
            )
            assertEquals(time, source.toGoalUi(xp, emptyMap(), emptyMap(), null).firstSeenAt)
            assertEquals(time, source.toBacklogUi(xp, emptyMap(), emptyMap(), null).firstSeenAt)
        }
    }
}
