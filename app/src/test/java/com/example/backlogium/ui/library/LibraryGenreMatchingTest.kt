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
        assertEquals(listOf(3L, 2L), rows.matching("IND").map { it.appId })
    }

    @Test fun simultaneousNameAndGenreMatchAppearsOnlyOnceAndBlankQueryClears() {
        assertEquals(listOf(3L, 1L), rows.matching("action").map { it.appId })
        assertEquals(rows, rows.matching("  "))
        assertEquals(emptyList<Long>(), rows.matching("strategy").map { it.appId })
    }

    @Test fun matchingRetainsEachNonEmptySection() {
        val focus = rows.take(1).matching("action")
        val backlog = rows.drop(1).matching("action")
        assertEquals(listOf(1L), focus.map { it.appId })
        assertEquals(listOf(3L), backlog.map { it.appId })
    }

    @Test fun prefixBeatsMidWordAndNameBeatsGenre() {
        val ranked = listOf(
            row(5, "Hundred Days", listOf(GameGenre("1", "Action"))),
            row(6, "Red Dead Redemption", emptyList()),
            row(7, "Portal", listOf(GameGenre("2", "Red"))),
        )

        assertEquals(listOf(6L, 5L, 7L), ranked.matching("red").map { it.appId })
    }

    private fun row(appId: Long, name: String, genres: List<GameGenre>) = BacklogGameUi(
        appId = appId, name = name, iconUrl = "", playtimeForever = 0, genres = genres,
    )
}
