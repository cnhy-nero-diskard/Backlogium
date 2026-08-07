package com.example.backlogium.ui.gamedetail

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * What the detail screen's genre tiles are handed. `GenreTiles` renders one `Surface` per element of
 * [GameSummaryUi.genres] in a wrapping `FlowRow` and returns early on an empty list, so the summary
 * mapping decides all three things this feature promised: which labels appear, in what order, and
 * whether the section exists at all.
 *
 * Genres are *informational*. The state carries the Store's label and id and nothing else — no
 * route, no filter target, no callback — so there is nothing a tile could navigate to even if one
 * were made clickable later. The layout itself (wrapping, spacing) is Compose behaviour and is
 * checked on-device per task 8.3; this module has no Compose test dependency.
 */
class GameSummaryGenreMappingTest {

    private val action = GameGenre("1", "Action")
    private val indie = GameGenre("23", "Indie")
    private val casual = GameGenre("4", "Casual")

    @Test
    fun everyGenreIsCarriedThrough_inStoreOrder() {
        // Enough labels to wrap onto several rows, in an order no comparator would produce.
        val ordered = listOf(indie, action, casual, GameGenre("9", "Racing"), GameGenre("2", "Strategy"))

        val summary = content(genres = ordered).toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(ordered, summary.genres)
    }

    @Test
    fun unknownGenresProduceNoSectionAndNoPlaceholder() {
        // An un-enriched game and a game the Store said has no genres are indistinguishable here,
        // which is the point: neither may render an empty box or an error line.
        val summary = content(genres = emptyList()).toSummary(rows = emptyList(), activePlayers = null)

        assertTrue(summary.genres.isEmpty())
    }

    @Test
    fun aMissingGameCarriesNoGenres() {
        // The game hasn't loaded (or the app id isn't in the library): the summary is the empty one.
        val summary = Content(
            game = null,
            achievements = emptyList(),
            trackedMinutes = 0,
            config = RuleConfig(),
        ).toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(GameSummaryUi(), summary)
        assertTrue(summary.genres.isEmpty())
    }

    @Test
    fun aGenreTileHasNothingToActOn() {
        // Structural, deliberately: the tile is a label because the model is only a label.
        val payload = GameGenre::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }

        assertEquals(listOf("id", "label"), payload)
    }

    private fun content(genres: List<GameGenre>) = Content(
        game = LibraryGame(
            appId = 1L, name = "Portal", iconUrl = "", playtimeForever = 120, genres = genres,
        ),
        achievements = emptyList(),
        trackedMinutes = 0,
        config = RuleConfig(),
    )
}
