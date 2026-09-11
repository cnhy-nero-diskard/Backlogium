package com.example.backlogium.domain.gapplan

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.SteamReviewCache
import com.example.backlogium.data.repo.GameCategory
import com.example.backlogium.data.repo.GameCategoryCodec
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.GameGenreCodec
import com.example.backlogium.data.repo.GameGenreRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.GameReviewSummary
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.OfflineHltbSource
import com.example.backlogium.data.repo.HltbDatasetLookup
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.data.repo.OfflineSteamApiDouble
import com.example.backlogium.data.repo.OfflineStoreApi
import com.example.backlogium.data.repo.PersonalPaceRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SteamReviewRepository
import com.example.backlogium.data.repo.SteamStoreGenreDataSource
import com.example.backlogium.data.repo.SteamStoreReviewDataSource
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one join gap planning reads, exercised over the real repositories and the real DAO SQL.
 *
 * Three things here would each silently corrupt every plan if they were wrong, and none of them is
 * visible from the engine's own tests: which session aggregate supplies playtime, whether unknown
 * participation categories can masquerade as single-player, and whether "today" is read fresh.
 */
@RunWith(RobolectricTestRunner::class)
class GapPlanFeedTest {

    private lateinit var db: BacklogiumDatabase
    private val zone: ZoneId = ZoneId.of("UTC")
    private var today: LocalDate = TODAY

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test
    fun theFeedJoinsPlaytimeGenresCategoriesAndReviewsIntoEngineInputs() = runTest {
        db.gameDao().upsertAll(listOf(game(1), game(2)))
        hltb(1, mainStory = 600, completionist = 1_800)
        genres(1, listOf(GameGenre("1", "Action")), listOf(GameCategory(1, "Multi-player")))
        genres(2, listOf(GameGenre("23", "Indie")), emptyList())
        db.steamReviewCacheDao().upsert(
            SteamReviewCache(
                appId = 1, description = "Very Positive", positive = 900, negative = 100,
                total = 1_000, available = true, checkedAt = 1,
            ),
        )

        val inputs = feed().inputs.first()

        val byAppId = inputs.games.associateBy { it.appId }
        assertEquals(600, byAppId.getValue(1L).mainStoryMinutes)
        assertEquals(1_800, byAppId.getValue(1L).completionistMinutes)
        assertEquals(listOf("1"), byAppId.getValue(1L).genreIds)
        assertTrue(byAppId.getValue(1L).multiplayer)
        assertFalse("an answered empty category list is not multiplayer", byAppId.getValue(2L).multiplayer)
        assertEquals(
            GameReviewSummary.Available("Very Positive", 900, 100, 1_000),
            inputs.reviewsByAppId[1L],
        )
        assertEquals(mapOf("1" to "Action", "23" to "Indie"), inputs.genreLabels)
        assertEquals(TODAY, inputs.today)
    }

    /**
     * Unknown categories must not read as single-player. A game the enrichment has not reached is
     * simply not offered a live lookup — which costs nothing — rather than being asserted to be
     * something it has never been checked for.
     */
    @Test fun aGameWithNoCategoryPayloadIsNotClassifiedMultiplayer() = runTest {
        db.gameDao().upsertAll(listOf(game(1), game(2)))
        // Row present, categories never retrieved.
        db.gameGenreCacheDao().upsert(
            GameGenreCache(1, "[]", checkedAt = 1, categoriesJson = null),
        )
        // No row at all.

        val games = feed().inputs.first().games.associateBy { it.appId }

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
        db.gameDao().upsert(
            game(1).copy(source = GameSource.FAMILY_SHARED, playtimeForever = 0, manualSharedMinutes = 120),
        )
        hltb(1, mainStory = 900, completionist = 900)
        // One session inside the affinity window and one far outside it. Both count as playtime.
        session(appId = 1, daysAgo = 2, minutes = 100)
        session(appId = 1, daysAgo = 400, minutes = 200)

        val inputs = feed().inputs.first()

        val shared = inputs.games.single()
        assertEquals(GameSource.FAMILY_SHARED, shared.source)
        assertEquals(300, shared.trackedMinutes)
        assertEquals(120, shared.manualSharedMinutes)
        // Only the in-window session reaches affinity.
        assertEquals(listOf(today.minusDays(2)), inputs.playedDates.map { it.date })
    }

    @Test fun sessionsOutsideTheAffinityWindowAreNotOfferedToGenreAffinity() = runTest {
        db.gameDao().upsert(game(1))
        session(appId = 1, daysAgo = 1, minutes = 30)
        session(appId = 1, daysAgo = GenreAffinity.LOOKBACK_DAYS.toInt() + 5, minutes = 30)

        assertEquals(1, feed().inputs.first().playedDates.size)
    }

    /**
     * The planning window follows a freshly read date. `PersonalPaceRepository` is a `@Singleton`
     * that captures its own `today` at construction, so a long-lived process that crossed local
     * midnight would otherwise plan from a stale "tomorrow" and forecast a day it no longer has.
     */
    @Test fun aDateRolloverBetweenConstructionAndGenerationMovesThePlanningWindow() = runTest {
        db.gameDao().upsert(game(1))
        hltb(1, mainStory = 600, completionist = 1_800)
        // Enough tracked history for a reliable profile, so the forecast path is the one exercised
        // rather than a manual budget that would not read the window at all.
        (1..40).forEach { session(appId = 1, daysAgo = it, minutes = 120) }
        val feed = feed()
        val request = gapRequest(targetDate = TODAY.plusDays(30))

        val before = feed.inputs.first()
        assertEquals(TODAY, before.today)
        assertTrue(before.paceProfile.isReliable)
        val beforeCapacity = request.resolveCapacity(before.paceProfile, before.today).getOrThrow()
        assertEquals(TODAY.plusDays(1), beforeCapacity.startDate)

        // Midnight passes. The feed is the same instance the pace repository was built against,
        // and that repository captured its own `today` when it was constructed.
        today = TODAY.plusDays(1)
        val after = feed.inputs.first()

        assertEquals(TODAY.plusDays(1), after.today)
        val afterCapacity = request.resolveCapacity(after.paceProfile, after.today).getOrThrow()
        // The window starts a day later and therefore covers one day less of the same target date.
        assertEquals(TODAY.plusDays(2), afterCapacity.startDate)
        assertEquals(TODAY.plusDays(30), afterCapacity.endDate)
        assertTrue(afterCapacity.fullCapacityMinutes < beforeCapacity.fullCapacityMinutes)
    }

    @Test fun anOfflineLibraryWithNoCachedMetadataStillProducesUsableInputs() = runTest {
        db.gameDao().upsertAll(listOf(game(1), game(2)))
        hltb(1, mainStory = 600, completionist = 1_800)

        val inputs = feed().inputs.first()

        assertEquals(2, inputs.games.size)
        assertTrue(inputs.reviewsByAppId.isEmpty())
        assertTrue(inputs.genreLabels.isEmpty())
        assertTrue(inputs.games.all { it.genreIds.isEmpty() && !it.multiplayer })
        // And the engine still plans from it.
        val snapshot = GapPlanEngine.generate(
            gapRequest(manualTotalHours = 20),
            inputs.copy(paceProfile = learningPace(inputs.today)),
        ).getOrThrow()
        assertEquals(listOf(1L), snapshot.variant(PlanIntensity.FULL)!!.members.map { it.appId })
    }

    private fun feed(): GapPlanFeed {
        val time = MovingTime()
        val hidden = HiddenGamesRepository(
            hiddenGameDao = db.hiddenGameDao(),
            gameDao = db.gameDao(),
            storeCacheDao = db.gameGenreCacheDao(),
            time = time,
        )
        val sessions = SessionRepository(db.sessionDao(), hidden)
        val genreRepository = GameGenreRepository(
            cacheDao = db.gameGenreCacheDao(),
            store = SteamStoreGenreDataSource(OfflineStoreApi),
            time = time,
        )
        return GapPlanFeed(
            gameRepository = GameRepository(
                gameDao = db.gameDao(),
                hltbRepository = HltbRepository(
                    dataSource = OfflineHltbSource,
                    hltbDataDao = db.hltbDataDao(),
                    datasetLookup = HltbDatasetLookup { null },
                    hiddenGameDao = db.hiddenGameDao(),
                    json = Json,
                    time = time,
                ),
                gameGenreRepository = genreRepository,
                hiddenGamesRepository = hidden,
                steamApi = OfflineSteamApiDouble,
                sessionRepository = sessions,
                time = time,
            ),
            sessionRepository = sessions,
            paceRepository = PersonalPaceRepository(sessions, time),
            genreRepository = genreRepository,
            reviewRepository = SteamReviewRepository(
                cacheDao = db.steamReviewCacheDao(),
                store = SteamStoreReviewDataSource(OfflineStoreApi),
                time = time,
            ),
            currentDate = CurrentDateProvider(time),
        )
    }

    private suspend fun genres(appId: Long, genres: List<GameGenre>, categories: List<GameCategory>) =
        db.gameGenreCacheDao().upsert(
            GameGenreCache(
                appId = appId,
                genresJson = GameGenreCodec.encode(genres),
                checkedAt = 1,
                categoriesJson = GameCategoryCodec.encode(categories),
            ),
        )

    private suspend fun hltb(appId: Long, mainStory: Int, completionist: Int) =
        db.hltbDataDao().upsert(
            HltbData(
                appId = appId,
                hltbId = appId,
                mainStoryMinutes = mainStory,
                mainExtraMinutes = null,
                completionistMinutes = completionist,
                allStylesMinutes = null,
                fetchedAt = 1,
                matchStatus = HltbMatchStatus.RESOLVED,
                candidatesJson = null,
            ),
        )

    private suspend fun session(appId: Long, daysAgo: Int, minutes: Int) {
        val startAt = today.minusDays(daysAgo.toLong())
            .atStartOfDay(zone)
            .plusHours(12)
            .toInstant()
            .toEpochMilli()
        db.sessionDao().insert(
            Session(appId = appId, startAt = startAt, endAt = startAt + 1, minutes = minutes, open = false),
        )
    }

    private fun game(appId: Long) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0,
    )

    /** Follows the test's own [today], so a rollover is a one-line change rather than a new object. */
    private inner class MovingTime : TimeProvider {
        override fun nowMillis(): Long =
            today.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli()

        override fun zone(): ZoneId = zone
        override fun today(): LocalDate = today
    }

}
