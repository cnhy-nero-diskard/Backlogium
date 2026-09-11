package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.SteamReviewCache
import com.example.backlogium.data.repo.GameCategory
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.GameReviewSummary
import com.example.backlogium.domain.GameSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one join gap planning reads, exercised over the real repositories and the real DAO SQL with
 * every network path broken.
 *
 * Three things here would each silently corrupt every plan if they were wrong, and none of them is
 * visible from the engine's own tests: which session aggregate supplies playtime, whether unknown
 * participation categories can masquerade as single-player, and whether "today" is read fresh.
 */
@RunWith(RobolectricTestRunner::class)
class GapPlanFeedTest {

    private lateinit var env: GapPlanTestEnvironment

    @Before fun setUp() {
        env = GapPlanTestEnvironment()
    }

    @After fun tearDown() = env.close()

    @Test
    fun theFeedJoinsPlaytimeGenresCategoriesAndReviewsIntoEngineInputs() = runTest {
        env.addGame(1)
        env.addGame(2)
        env.addHltb(1, mainStory = 600, completionist = 1_800)
        env.addStoreMetadata(
            1,
            genres = listOf(GameGenre("1", "Action")),
            categories = listOf(GameCategory(1, "Multi-player")),
        )
        env.addStoreMetadata(2, genres = listOf(GameGenre("23", "Indie")), categories = emptyList())
        env.addReview(1, "Very Positive", positive = 900, negative = 100)

        val snapshot = env.feed.snapshots.first()

        val byAppId = snapshot.inputs.games.associateBy { it.appId }
        assertEquals(600, byAppId.getValue(1L).mainStoryMinutes)
        assertEquals(1_800, byAppId.getValue(1L).completionistMinutes)
        assertEquals(listOf("1"), byAppId.getValue(1L).genreIds)
        assertTrue(byAppId.getValue(1L).multiplayer)
        assertFalse(
            "an answered empty category list is not multiplayer",
            byAppId.getValue(2L).multiplayer,
        )
        assertEquals(
            GameReviewSummary.Available("Very Positive", 900, 100, 1_000),
            snapshot.inputs.reviewsByAppId[1L],
        )
        assertEquals(mapOf("1" to "Action", "23" to "Indie"), snapshot.inputs.genreLabels)
        assertEquals(TODAY, snapshot.inputs.today)
        // Artwork travels in the same emission, so a card cannot show art from a different one.
        assertEquals("icon-1", snapshot.artwork.getValue(1L).iconUrl)
    }

    /**
     * Unknown categories must not read as single-player. A game the enrichment has not reached is
     * simply not offered a live lookup — which costs nothing — rather than being asserted to be
     * something it has never been checked for.
     */
    @Test fun aGameWithNoCategoryPayloadIsNotClassifiedMultiplayer() = runTest {
        env.addGame(1)
        env.addGame(2)
        // Row present, categories never retrieved.
        env.db.gameGenreCacheDao().upsert(
            GameGenreCache(1, "[]", checkedAt = 1, categoriesJson = null),
        )
        // 2 has no row at all.

        val games = env.feed.snapshots.first().inputs.games.associateBy { it.appId }

        assertFalse(games.getValue(1L).multiplayer)
        assertFalse(games.getValue(2L).multiplayer)
    }

    /**
     * The two session reads are different questions and must not be conflated. All-time tracked
     * minutes make a family-shared game's playtime truthful; the windowed rows are what affinity
     * reads. Using the window for playtime would understate every shared game's progress and
     * re-plan work already done.
     */
    @Test fun familySharedPlaytimeUsesAllTimeTrackedMinutesNotTheAffinityWindow() = runTest {
        env.addGame(1, source = GameSource.FAMILY_SHARED, manualSharedMinutes = 120)
        env.addHltb(1, mainStory = 900)
        // One session inside the affinity window and one far outside it. Both count as playtime.
        env.addSession(appId = 1, daysAgo = 2, minutes = 100)
        env.addSession(appId = 1, daysAgo = 400, minutes = 200)

        val inputs = env.feed.snapshots.first().inputs

        val shared = inputs.games.single()
        assertEquals(GameSource.FAMILY_SHARED, shared.source)
        assertEquals(300, shared.trackedMinutes)
        assertEquals(120, shared.manualSharedMinutes)
        // Only the in-window session reaches affinity.
        assertEquals(listOf(TODAY.minusDays(2)), inputs.playedDates.map { it.date })
    }

    @Test fun sessionsOutsideTheAffinityWindowAreNotOfferedToGenreAffinity() = runTest {
        env.addGame(1)
        env.addSession(appId = 1, daysAgo = 1, minutes = 30)
        env.addSession(appId = 1, daysAgo = GenreAffinity.LOOKBACK_DAYS.toInt() + 5, minutes = 30)

        assertEquals(1, env.feed.snapshots.first().inputs.playedDates.size)
    }

    /**
     * The planning window follows a freshly read date. `PersonalPaceRepository` is a `@Singleton`
     * that captures its own `today` at construction, so a long-lived process that crossed local
     * midnight would otherwise plan from a stale "tomorrow" and forecast a day it no longer has.
     */
    @Test fun aDateRolloverBetweenConstructionAndGenerationMovesThePlanningWindow() = runTest {
        env.addGame(1)
        env.addHltb(1, mainStory = 600, completionist = 1_800)
        env.seedReliablePace(appId = 1)
        val request = gapRequest(targetDate = TODAY.plusDays(30))

        val before = env.feed.snapshots.first().inputs
        assertEquals(TODAY, before.today)
        assertTrue(before.paceProfile.isReliable)
        val beforeCapacity = request.resolveCapacity(before.paceProfile, before.today).getOrThrow()
        assertEquals(TODAY.plusDays(1), beforeCapacity.startDate)

        // Midnight passes. The feed is the same instance the pace repository was built against,
        // and that repository captured its own `today` when it was constructed.
        env.today = TODAY.plusDays(1)
        val after = env.feed.snapshots.first().inputs

        assertEquals(TODAY.plusDays(1), after.today)
        val afterCapacity = request.resolveCapacity(after.paceProfile, after.today).getOrThrow()
        // The window starts a day later and therefore covers one day less of the same target date.
        assertEquals(TODAY.plusDays(2), afterCapacity.startDate)
        assertEquals(TODAY.plusDays(30), afterCapacity.endDate)
        assertTrue(afterCapacity.fullCapacityMinutes < beforeCapacity.fullCapacityMinutes)
    }

    @Test fun anOfflineLibraryWithNoCachedMetadataStillProducesUsableInputs() = runTest {
        env.addGame(1)
        env.addGame(2)
        env.addHltb(1, mainStory = 600, completionist = 1_800)

        val inputs = env.feed.snapshots.first().inputs

        assertEquals(2, inputs.games.size)
        assertTrue(inputs.reviewsByAppId.isEmpty())
        assertTrue(inputs.genreLabels.isEmpty())
        assertTrue(inputs.games.all { it.genreIds.isEmpty() && !it.multiplayer })
        // And the engine still offers a pick from it.
        val snapshot = GapPlanEngine.generate(
            gapRequest(manualTotalHours = 20),
            inputs.copy(paceProfile = learningPace(inputs.today)),
            seed = 1L,
        ).getOrThrow()
        assertEquals(listOf(1L), snapshot.pickedAppIds)
    }

    /** A checked-unavailable review row stays distinct from never having asked. */
    @Test fun anUnavailableReviewRowIsDistinctFromAMissingOne() = runTest {
        env.addGame(1)
        env.addGame(2)
        env.db.steamReviewCacheDao().upsert(
            SteamReviewCache(appId = 1, available = false, checkedAt = 1),
        )

        val reviews = env.feed.snapshots.first().inputs.reviewsByAppId

        assertEquals(GameReviewSummary.Unavailable, reviews[1L])
        assertFalse(reviews.containsKey(2L))
    }
}
