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
                name = "short game with recorded imported playtime is not a quick win",
                game = game(playtime = 10, mainStory = 240),
                expected = emptySet(),
            ),
            fixture(
                name = "unstarted game without HLTB length is not a quick win",
                game = game(playtime = 0, mainStory = null),
                expected = setOf(SmartCollectionId.NEVER_STARTED),
            ),
            fixture(
                name = "imported playtime without session history is not dropped",
                game = game(playtime = 300, mainStory = 600),
                achievements = noAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "barely started game is not dropped",
                game = game(playtime = 40, mainStory = 600),
                session = oldSession(),
                achievements = lockedAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "recent meaningful play is not dropped",
                game = game(playtime = 300, mainStory = 600),
                session = session(today.minusDays(7)),
                achievements = lockedAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "a game at exactly thirty idle days is not dropped",
                game = game(playtime = 300, mainStory = 600),
                session = session(today.minusDays(30)),
                achievements = lockedAchievements(),
                expected = emptySet(),
            ),
            fixture(
                name = "a game crossing the idle boundary is dropped",
                game = game(playtime = 300, mainStory = 600),
                session = session(today.minusDays(31)),
                achievements = lockedAchievements(),
                expected = setOf(SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "a brief relaunch does not rescue a dropped game",
                game = game(playtime = 300, mainStory = 600),
                // The ten-minute relaunch is filtered out before these signals reach the domain.
                session = oldSession(),
                achievements = lockedAchievements(),
                expected = setOf(SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "a qualifying session supplies dropped recency",
                game = game(playtime = 300, mainStory = 600),
                session = session(today.minusDays(31)),
                achievements = lockedAchievements(),
                expected = setOf(SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "nearly finished and abandoned game overlaps both lists",
                game = game(playtime = 510, mainStory = 600),
                session = oldSession(),
                achievements = lockedAchievements(),
                expected = setOf(SmartCollectionId.ALMOST_DONE, SmartCollectionId.DROPPED),
            ),
            fixture(
                name = "locked achievements prevent completion despite sufficient playtime",
                game = game(playtime = 600, mainStory = 600),
                achievements = lockedAchievements(),
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
                expected = setOf(SmartCollectionId.ALMOST_DONE),
            ),
            fixture(
                name = "a sub-threshold session alone is neither play nor dropped history",
                game = game(playtime = 0, mainStory = 240),
                // An under-fifteen-minute session is absent from the meaningful aggregate.
                session = MeaningfulSessionSignals(),
                expected = setOf(SmartCollectionId.QUICK_WINS, SmartCollectionId.NEVER_STARTED),
            ),
        )

        fixtures.forEach { fixture ->
            val result = SmartCollections.derive(
                games = listOf(fixture.game),
                sessionsByGame = mapOf(fixture.game.appId to fixture.session),
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
    fun shortOwnedSessionDoesNotBecomeDerivedPlaytime() {
        val derivedPlaytime = smartCollectionPlaytimeMinutes(
            source = GameSource.STEAM_OWNED,
            steamPlaytimeMinutes = 10,
            importedPlaytimeMinutes = 0,
            totalSessionMinutes = 10,
            meaningfulSessionMinutes = 0,
        )

        val result = SmartCollections.derive(
            games = listOf(game(playtime = derivedPlaytime, mainStory = 240)),
            sessionsByGame = emptyMap(),
            achievementsByGame = emptyMap(),
            today = today,
        )

        assertEquals(0, derivedPlaytime)
        assertTrue(result.neverStarted.isNotEmpty())
        assertTrue(result.quickWins.isNotEmpty())
    }

    @Test
    fun importedAndMeaningfulPlaytimeSurviveShortSessionFiltering() {
        assertEquals(
            100,
            smartCollectionPlaytimeMinutes(
                source = GameSource.STEAM_OWNED,
                steamPlaytimeMinutes = 110,
                importedPlaytimeMinutes = 100,
                totalSessionMinutes = 10,
                meaningfulSessionMinutes = 0,
            ),
        )
        assertEquals(
            15,
            smartCollectionPlaytimeMinutes(
                source = GameSource.FAMILY_SHARED,
                steamPlaytimeMinutes = 0,
                importedPlaytimeMinutes = 0,
                totalSessionMinutes = 15,
                meaningfulSessionMinutes = 15,
            ),
        )
        assertEquals(
            120,
            smartCollectionPlaytimeMinutes(
                source = GameSource.STEAM_OWNED,
                steamPlaytimeMinutes = 100,
                importedPlaytimeMinutes = 100,
                totalSessionMinutes = 20,
                meaningfulSessionMinutes = 20,
            ),
        )
    }

    @Test
    fun completionBasisIsOnlyDisclosedForCompletedMembers() {
        val game = game(playtime = 0, mainStory = 240)
        val result = SmartCollections.derive(
            games = listOf(game),
            sessionsByGame = emptyMap(),
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
            sessionsByGame = emptyMap(),
            achievementsByGame = mapOf(
                game.appId to SmartCollectionAchievementSignals(
                    unlocked = 2,
                    total = 3,
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
        val session: MeaningfulSessionSignals,
        val achievements: SmartCollectionAchievementSignals,
        val expected: Set<SmartCollectionId>,
        val completionBasis: CompletionBasis? = null,
    )

    private fun fixture(
        name: String,
        game: SmartCollectionGame,
        expected: Set<SmartCollectionId>,
        session: MeaningfulSessionSignals = MeaningfulSessionSignals(),
        achievements: SmartCollectionAchievementSignals = SmartCollectionAchievementSignals(),
        completionBasis: CompletionBasis? = null,
    ) = Fixture(name, game, session, achievements, expected, completionBasis)

    private fun game(playtime: Int, mainStory: Int?, appId: Long = nextId++) =
        SmartCollectionGame(
            appId = appId,
            name = "Game $appId",
            playtimeMinutes = playtime,
            mainStoryMinutes = mainStory,
        )

    private fun session(date: LocalDate) = MeaningfulSessionSignals(
        meaningfulSessionCount = 1,
        lastMeaningfulSessionAt = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        meaningfulMinutes = MEANINGFUL_SESSION_MINUTES,
    )

    private fun oldSession() = session(today.minusDays(31))

    private fun lockedAchievements() = SmartCollectionAchievementSignals(
        unlocked = 1,
        total = 2,
        state = AchievementDataState.HAS_ACHIEVEMENTS,
    )

    private fun noAchievements() = SmartCollectionAchievementSignals(
        state = AchievementDataState.NO_ACHIEVEMENTS,
    )

    private companion object {
        var nextId = 1L
    }
}
