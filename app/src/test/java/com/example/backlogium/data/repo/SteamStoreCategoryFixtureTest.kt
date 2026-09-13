package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.dto.StoreAppDetails
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The participation-category constant table, checked against real captured `appdetails` responses
 * rather than against itself.
 *
 * Every assertion here reads the id **out of the fixture** and compares it to the constant. A
 * remembered-but-wrong id therefore cannot pass: it would have to also appear in Steam's own
 * answer for a game of that kind. Guessing at these ids is the specific failure this test exists
 * to prevent, because a wrong id produces no error at all — only a multiplayer library silently
 * classified single-player.
 *
 * The fixtures are described in `src/test/resources/.../store/README.md`.
 */
class SteamStoreCategoryFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun multiPlayerId_isWhatDota2Advertises() {
        val categories = categoriesOf(appId = 570)
        assertEquals(
            "Multi-player",
            categories.single { it.id == ParticipationCategories.MULTI_PLAYER }.label,
        )
        assertTrue(categories.advertisesMultiplayer())
    }

    @Test fun onlineCoOpId_isWhatDeepRockGalacticAdvertises() {
        val categories = categoriesOf(appId = 548430)
        assertEquals(
            "Online Co-op",
            categories.single { it.id == ParticipationCategories.ONLINE_CO_OP }.label,
        )
        assertTrue(categories.advertisesMultiplayer())
    }

    @Test fun massivelyMultiplayerId_isWhatPathOfExileAdvertises() {
        val categories = categoriesOf(appId = 238960)
        assertEquals("MMO", categories.single { it.id == ParticipationCategories.MASSIVELY_MULTIPLAYER }.label)
        assertTrue(categories.advertisesMultiplayer())
    }

    /**
     * The control. Portal carries a full category list — achievements, controller support, Family
     * Sharing — so "no match" here is a real negative rather than an empty response.
     */
    @Test fun aSinglePlayerOnlyAppMatchesNothingInTheTable() {
        val categories = categoriesOf(appId = 400)
        assertTrue("the fixture must be a populated list", categories.size > 5)
        assertEquals("Single-player", categories.single { it.id == SINGLE_PLAYER }.label)
        assertFalse(categories.advertisesMultiplayer())
    }

    /**
     * Steam sends a category id as a JSON number and a genre id as a JSON string. Parsing one real
     * response through both narrow DTOs at once is what proves they need separate shapes.
     */
    @Test fun genresAndCategoriesAreDecodedSeparatelyFromOneResponse() {
        val data = envelope(appId = 238960).data!!
        val genres = data.genres.toGameGenres()
        val categories = data.categories.toGameCategories()

        assertTrue(genres.isNotEmpty())
        assertTrue(categories.isNotEmpty())
        // Categories never leak into genres: "Multi-player" is not a kind of game.
        assertFalse(genres.any { it.label == "Multi-player" || it.label == "MMO" })
        // Genre ids are strings straight from Steam; category ids are numbers.
        assertTrue(genres.all { it.id.toIntOrNull() != null && it.id.isNotBlank() })
    }

    private fun categoriesOf(appId: Long): List<GameCategory> =
        envelope(appId).data!!.categories.toGameCategories()

    private fun envelope(appId: Long): StoreAppDetails {
        val body = checkNotNull(
            javaClass.getResourceAsStream("/com/example/backlogium/data/remote/store/appdetails-$appId.json"),
        ) { "missing captured fixture for $appId" }.use { it.readBytes().decodeToString() }
        return json.decodeFromString<Map<String, StoreAppDetails>>(body).getValue(appId.toString())
    }

    private companion object {
        /** Steam category `2`. Named only so the control test can assert the fixture is populated. */
        const val SINGLE_PLAYER = 2
    }
}
