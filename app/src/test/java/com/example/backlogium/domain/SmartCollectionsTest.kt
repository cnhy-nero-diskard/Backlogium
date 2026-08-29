package com.example.backlogium.domain

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM fixtures for the pure smart-collection derivation. */
class SmartCollectionsTest {

    private val today = LocalDate.of(2026, 8, 4)

    @Test
    fun fixtureTable_coversMembershipBoundariesAndMissingSignals() {
        val fixtures = listOf(
            fixture(
                name = "short unstarted game is both quick win and never started",
                game = game(playtime = 0, mainStory = 240),
                expected = setOf(SmartCollectionId.QUICK_WINS, SmartCollectionId.NEVER_STARTED),
            ),
            fixture(
                name = "long unstarted game is only never started",
                game = game(playtime = 0, mainStory = 2_400),
                expected = setOf(SmartCollectionId.NEVER_STARTED),
            ),
            fixture(
                name = "short game with recorded playtime is not a quick win",
                game = game(playtime = 10, mainStory = 240),
                expected = emptySet(),
            ),
            fixture(
                name = "unstarted game without HLTB length is not a quick win",
                game = game(playtime = 0, mainStory = null),
                expected = setOf(SmartCollectionId.NEVER_STARTED),
            ),
            fixture(
                name = "playtime with no known last play is not dropped",
                game = game(playtime = 300, mainStory = 600, lastPlayed = null),
                achievements = noAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "steam's own last-played stamp supplies dropped recency",
                game = game(playtime = 300, mainStory = 600, lastPlayed = today.minusDays(90)),
                achievements = lockedAchievements(),
                expected = setOf(SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "an hour of playtime is below the dropped floor",
                game = game(playtime = 60, mainStory = 600, lastPlayed = today.minusDays(90)),
                achievements = lockedAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "exactly ninety minutes is still below the dropped floor",
                game = game(playtime = 90, mainStory = 600, lastPlayed = today.minusDays(90)),
                achievements = lockedAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "ninety-one minutes clears the dropped floor",
                game = game(playtime = 91, mainStory = 600, lastPlayed = today.minusDays(90)),
                achievements = lockedAchievements(),
                expected = setOf(SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "recent play is not dropped",
                game = game(playtime = 300, mainStory = 600, lastPlayed = today.minusDays(7)),
                achievements = lockedAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "a game at exactly thirty idle days is not dropped",
                game = game(playtime = 300, mainStory = 600, lastPlayed = today.minusDays(30)),
                achievements = lockedAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "a game crossing the idle boundary is dropped",
                game = game(playtime = 300, mainStory = 600, lastPlayed = today.minusDays(31)),
                achievements = lockedAchievements(),
                expected = setOf(SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "nearly finished and abandoned game overlaps both lists",
                game = game(playtime = 510, mainStory = 600, lastPlayed = today.minusDays(31)),
                achievements = nearlyUnlockedAchievements(),
                expected = setOf(SmartCollectionId.ALMOST_DONE, SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "half-unlocked achievements keep a long-played game out of almost done",
                game = game(playtime = 2_400, mainStory = 1_920),
                achievements = SmartCollectionAchievementSignals(
                    unlocked = 40,
                    total = 100,
                    state = AchievementDataState.HAS_ACHIEVEMENTS,
                ),
                expected = emptySet(),
            ),
            fixture(
                name = "one locked achievement out of ten still qualifies as almost done",
                game = game(playtime = 600, mainStory = 600),
                achievements = SmartCollectionAchievementSignals(
                    unlocked = 9,
                    total = 10,
                    state = AchievementDataState.HAS_ACHIEVEMENTS,
                ),
                expected = setOf(SmartCollectionId.ALMOST_DONE),
            ),
            fixture(
                name = "an achievementless game is judged on playtime alone",
                game = game(playtime = 500, mainStory = 600),
                achievements = noAchievements(),
                expected = setOf(SmartCollectionId.ALMOST_DONE),
            ),
            fixture(
                name = "all achievements unlocked completes by achievements",
                game = game(playtime = 120, mainStory = 600),
                achievements = SmartCollectionAchievementSignals(
                    unlocked = 10,
                    total = 10,
                    state = AchievementDataState.HAS_ACHIEVEMENTS,
                ),
                expected = setOf(SmartCollectionId.COMPLETED),
                completionBasis = CompletionBasis.ACHIEVEMENTS,
            ),
            fixture(
                name = "genuinely achievementless game completes by main story playtime",
                game = game(playtime = 600, mainStory = 600),
                achievements = noAchievements(),
                expected = setOf(SmartCollectionId.COMPLETED),
                completionBasis = CompletionBasis.PLAYTIME,
            ),
            fixture(
                name = "achievementless game below main story is not complete",
                game = game(playtime = 360, mainStory = 600),
                achievements = noAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "missing HLTB length prevents playtime completion",
                game = game(playtime = 600, mainStory = null),
                achievements = noAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "missing achievement data does not trigger playtime fallback",
                game = game(playtime = 600, mainStory = 600),
                achievements = SmartCollectionAchievementSignals(),
                expected = emptySet(),
            ),
            fixture(
                name = "unfetched achievements keep a game out of almost done, not out of dropped",
                game = game(playtime = 510, mainStory = 600, lastPlayed = today.minusDays(31)),
                achievements = SmartCollectionAchievementSignals(),
                expected = setOf(SmartCollectionId.DROPPED),
            ),
        )

        fixtures.forEach { fixture ->
            val result = SmartCollections.derive(
                games = listOf(fixture.game),
                achievementsByGame = mapOf(fixture.game.appId to fixture.achievements),
                today = today,
            )
            val actual = SmartCollectionId.entries
                .filter { id -> result[id].any { it.game.appId == fixture.game.appId } }
                .toSet()

            assertEquals(fixture.name, fixture.expected, actual)
            if (fixture.completionBasis != null) {
                assertEquals(
                    fixture.name,
                    fixture.completionBasis,
                    result.completed.single().completionBasis,
                )
            } else {
                assertTrue(
                    "${fixture.name} unexpectedly completed",
                    result.completed.none { it.game.appId == fixture.game.appId },
                )
            }
        }
    }

    @Test
    fun ownedPlaytimePrefersSteamsTotalAndFallsBackToObservedHistory() {
        assertEquals(
            110,
            smartCollectionPlaytimeMinutes(
                source = GameSource.STEAM_OWNED,
                steamPlaytimeMinutes = 110,
                importedPlaytimeMinutes = 100,
                sessionMinutes = 10,
            ),
        )
        assertEquals(
            120,
            smartCollectionPlaytimeMinutes(
                source = GameSource.STEAM_OWNED,
                steamPlaytimeMinutes = 100,
                importedPlaytimeMinutes = 100,
                sessionMinutes = 20,
            ),
        )
        assertEquals(
            15,
            smartCollectionPlaytimeMinutes(
                source = GameSource.FAMILY_SHARED,
                steamPlaytimeMinutes = 0,
                importedPlaytimeMinutes = 0,
                sessionMinutes = 15,
            ),
        )
    }

    @Test
    fun anyTrackedSessionCountsAsPlay() {
        val derivedPlaytime = smartCollectionPlaytimeMinutes(
            source = GameSource.FAMILY_SHARED,
            steamPlaytimeMinutes = 0,
            importedPlaytimeMinutes = 0,
            sessionMinutes = 10,
        )

        val result = SmartCollections.derive(
            games = listOf(game(playtime = derivedPlaytime, mainStory = 240)),
            achievementsByGame = emptyMap(),
            today = today,
        )

        assertEquals(10, derivedPlaytime)
        assertTrue(result.neverStarted.isEmpty())
        assertTrue(result.quickWins.isEmpty())
    }

    @Test
    fun completionBasisIsOnlyDisclosedForCompletedMembers() {
        val game = game(playtime = 0, mainStory = 240)
        val result = SmartCollections.derive(
            games = listOf(game),
            achievementsByGame = mapOf(game.appId to noAchievements()),
            today = today,
        )

        assertEquals(game.appId, result.quickWins.single().game.appId)
        org.junit.Assert.assertNull(result.quickWins.single().completionBasis)
        org.junit.Assert.assertNull(result.neverStarted.single().completionBasis)
    }

    @Test
    fun completionUsesAchievementsBeforePlaytimeFallback() {
        val game = game(playtime = 600, mainStory = 600)
        val result = SmartCollections.derive(
            games = listOf(game),
            achievementsByGame = mapOf(
                game.appId to SmartCollectionAchievementSignals(
                    unlocked = 9,
                    total = 10,
                    state = AchievementDataState.HAS_ACHIEVEMENTS,
                ),
            ),
            today = today,
        )

        assertFalse(result.completed.any { it.game.appId == game.appId })
        assertTrue(result.almostDone.any { it.game.appId == game.appId })
    }

    private data class Fixture(
        val name: String,
        val game: SmartCollectionGame,
        val achievements: SmartCollectionAchievementSignals,
        val expected: Set<SmartCollectionId>,
        val completionBasis: CompletionBasis? = null,
    )

    private fun fixture(
        name: String,
        game: SmartCollectionGame,
        expected: Set<SmartCollectionId>,
        achievements: SmartCollectionAchievementSignals = SmartCollectionAchievementSignals(),
        completionBasis: CompletionBasis? = null,
    ) = Fixture(name, game, achievements, expected, completionBasis)

    private fun game(
        playtime: Int,
        mainStory: Int?,
        lastPlayed: LocalDate? = null,
        appId: Long = nextId++,
    ) = SmartCollectionGame(
        appId = appId,
        name = "Game $appId",
        playtimeMinutes = playtime,
        mainStoryMinutes = mainStory,
        lastPlayedAt = lastPlayed?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )

    private fun lockedAchievements() = SmartCollectionAchievementSignals(
        unlocked = 1,
        total = 2,
        state = AchievementDataState.HAS_ACHIEVEMENTS,
    )

    private fun nearlyUnlockedAchievements() = SmartCollectionAchievementSignals(
        unlocked = 8,
        total = 10,
        state = AchievementDataState.HAS_ACHIEVEMENTS,
    )

    private fun noAchievements() = SmartCollectionAchievementSignals(
        state = AchievementDataState.NO_ACHIEVEMENTS,
    )

    private companion object {
        var nextId = 1L
    }
}
