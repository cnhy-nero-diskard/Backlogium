package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.GameGenre
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGenreMatchingTest {
    private val rows = listOf(
        row(1, "Portal", listOf(GameGenre("1", "Action"))),
        row(2, "Celeste", listOf(GameGenre("23", "Indie"))),
        row(3, "Indie Action", listOf(GameGenre("1", "Action"))),
        row(4, "Unknown", emptyList()),
    )

    @Test fun nameSearchWorksWithoutGenreDataAndGenreSearchIsPartialCaseInsensitive() {
        assertEquals(listOf(4L), rows.matching("unknown").map { it.appId })
        assertEquals(listOf(2L, 3L), rows.matching("IND").map { it.appId })
    }

    @Test fun simultaneousNameAndGenreMatchAppearsOnlyOnceAndBlankQueryClears() {
        assertEquals(listOf(1L, 3L), rows.matching("action").map { it.appId })
        assertEquals(rows, rows.matching("  "))
        assertEquals(emptyList<Long>(), rows.matching("strategy").map { it.appId })
    }

    @Test fun matchingRetainsEachNonEmptySection() {
        val focus = rows.take(1).matching("action")
        val backlog = rows.drop(1).matching("action")
        assertEquals(listOf(1L), focus.map { it.appId })
        assertEquals(listOf(3L), backlog.map { it.appId })
    }

    private fun row(appId: Long, name: String, genres: List<GameGenre>) = BacklogGameUi(
        appId = appId, name = name, iconUrl = "", playtimeForever = 0, genres = genres,
    )
}
