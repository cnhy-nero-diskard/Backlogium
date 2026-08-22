package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A hidden game is absent from **every** read path, asserted one surface at a time against real
 * Room queries rather than against a mock of the filter itself (add-hidden-games).
 *
 * The point of the per-surface coverage is that exclusion is centralised: each of these surfaces
 * reads a repository flow, so a new screen built on the same flow inherits the exclusion instead
 * of having to remember it. The unhide half of each assertion is what proves nothing was deleted
 * to achieve the exclusion.
 */
@RunWith(RobolectricTestRunner::class)
class HiddenGamesExclusionTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var hidden: HiddenGamesRepository
    private lateinit var games: GameRepository
    private lateinit var sessions: SessionRepository
    private lateinit var collections: CollectionRepository
    private lateinit var achievements: AchievementRepository

    private val visibleAppId = 10L
    private val hiddenAppId = 20L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()

        hidden = HiddenGamesRepository(
            hiddenGameDao = db.hiddenGameDao(),
            gameDao = db.gameDao(),
            time = HiddenGamesTestTime,
        )
        games = GameRepository(
            gameDao = db.gameDao(),
            hltbRepository = HltbRepository(
                dataSource = OfflineHltbSource,
                hltbDataDao = db.hltbDataDao(),
                json = Json,
                time = HiddenGamesTestTime,
            ),
            gameGenreRepository = GameGenreRepository(
                cacheDao = db.gameGenreCacheDao(),
                store = SteamStoreGenreDataSource(OfflineStoreApi),
                time = HiddenGamesTestTime,
            ),
            hiddenGamesRepository = hidden,
            steamApi = OfflineSteamApiDouble,
        )
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            hiddenGamesRepository = hidden,
        )
        collections = CollectionRepository(
            collectionDao = db.collectionDao(),
            hiddenGamesRepository = hidden,
            time = HiddenGamesTestTime,
        )
        achievements = AchievementRepository(
            steamApi = OfflineSteamApiDouble,
            achievementDao = db.achievementDao(),
            gameAchievementSyncDao = db.gameAchievementSyncDao(),
            gameDao = db.gameDao(),
            hiddenGamesRepository = hidden,
            time = HiddenGamesTestTime,
        )

        db.gameDao().upsertAll(
            listOf(
                game(visibleAppId, name = "Visible Game", isGoal = true),
                game(hiddenAppId, name = "Wallpaper Engine", isGoal = true),
            ),
        )
        db.sessionDao().insert(session(visibleAppId, startAt = 1_000L, minutes = 30))
        db.sessionDao().insert(session(hiddenAppId, startAt = 500L, minutes = 90))
        db.achievementDao().upsertAll(
            listOf(
                achievement(visibleAppId, "visible_one"),
                achievement(hiddenAppId, "hidden_one"),
            ),
        )
        Unit
    }

    @After fun tearDown() = db.close()

    @Test
    fun library_omitsHiddenGame_andRestoresItOnUnhide() = runTest {
        assertEquals(
            listOf(visibleAppId, hiddenAppId),
            games.library.first().map { it.appId }.sorted(),
        )

        hidden.hide(listOf(hiddenAppId))

        assertEquals(listOf(visibleAppId), games.library.first().map { it.appId })

        hidden.unhide(listOf(hiddenAppId))

        assertEquals(
            listOf(visibleAppId, hiddenAppId),
            games.library.first().map { it.appId }.sorted(),
        )
    }

    /** Search reads the same flow the Library does, so one filter covers both surfaces. */
    @Test
    fun searchableLibrary_omitsHiddenGameByName() = runTest {
        hidden.hide(listOf(hiddenAppId))

        val matches = games.library.first().filter { it.name.contains("Wallpaper", ignoreCase = true) }

        assertTrue(matches.isEmpty())
    }

    @Test
    fun derivedLists_omitHiddenGame() = runTest {
        hidden.hide(listOf(hiddenAppId))

        assertEquals(listOf(visibleAppId), games.goalGames.first().map { it.appId })
        assertTrue(games.backlog.first().none { it.appId == hiddenAppId })
    }

    @Test
    fun history_omitsHiddenGameSessions() = runTest {
        hidden.hide(listOf(hiddenAppId))

        val listed = sessions.sessionsSince(0L).first()

        assertEquals(listOf(visibleAppId), listed.map { it.appId })
    }

    @Test
    fun analytics_omitsHiddenGameFromEveryPerGameFigure() = runTest {
        hidden.hide(listOf(hiddenAppId))

        assertEquals(mapOf(visibleAppId to 30), sessions.trackedMinutesByGame.first())
        assertEquals(mapOf(visibleAppId to 30), sessions.minutesByGameSince(0L).first())
        assertEquals(mapOf(visibleAppId to 1), sessions.sessionCountByGame.first())
        // The window anchor follows the visible history, not the hidden game's earlier session.
        assertEquals(1_000L, sessions.earliestSessionStart.first())
    }

    @Test
    fun analytics_reportsNoHistoryWhenOnlyHiddenGamesHaveSessions() = runTest {
        hidden.hide(listOf(visibleAppId, hiddenAppId))

        assertNull(sessions.earliestSessionStart.first())
        assertTrue(sessions.sessionsSince(0L).first().isEmpty())
    }

    @Test
    fun collections_omitHiddenMemberButRetainTheMembership() = runTest {
        val collectionId = db.collectionDao().insert(
            Collection(
                name = "Backlog",
                mode = CollectionMode.BASIC,
                sort = CollectionSort.NAME,
                createdAt = 0L,
            ),
        )
        db.collectionDao().addMember(collectionId, visibleAppId)
        db.collectionDao().addMember(collectionId, hiddenAppId)

        hidden.hide(listOf(hiddenAppId))

        assertEquals(listOf(visibleAppId), collections.members(collectionId).first().map { it.appId })
        assertEquals(listOf(visibleAppId), collections.getMembers(collectionId).map { it.appId })
        assertEquals(listOf(visibleAppId), collections.allMembers.first().map { it.appId })
        // The row itself is untouched, which is what lets unhiding restore membership.
        assertEquals(2, db.collectionDao().getMembers(collectionId).size)

        hidden.unhide(listOf(hiddenAppId))

        assertEquals(
            listOf(visibleAppId, hiddenAppId),
            collections.members(collectionId).first().map { it.appId }.sorted(),
        )
    }

    @Test
    fun achievements_omitHiddenGameFromEveryProjection() = runTest {
        hidden.hide(listOf(hiddenAppId))

        assertEquals(setOf(visibleAppId), achievements.counts.first().keys)
        assertEquals(setOf(visibleAppId), achievements.unlockedRarityByGame.first().keys)
        assertEquals(
            listOf(visibleAppId),
            achievements.unlockedSince(0L).first().map { it.appId },
        )
        assertTrue(achievements.unlockedRarityDetails.first().none { it.appId == hiddenAppId })
        // Nothing was deleted: the stored rows are still there for when the game is unhidden.
        assertEquals(1, db.achievementDao().getForGame(hiddenAppId).size)
    }

    @Test
    fun hiddenSet_reportsWhatIsHidden() = runTest {
        assertTrue(hidden.hiddenGames.first().isEmpty())
        assertFalse(hidden.isHidden(hiddenAppId))

        hidden.hide(listOf(hiddenAppId), fromBulkAction = true)

        val entries = hidden.hiddenGames.first()
        assertEquals(1, entries.size)
        assertEquals("Wallpaper Engine", entries.single().name)
        assertTrue(entries.single().fromBulkAction)
        assertTrue(hidden.isHidden(hiddenAppId))
        assertEquals(setOf(hiddenAppId), hidden.hiddenAppIdSet())

        hidden.unhideAll()

        assertTrue(hidden.hiddenGames.first().isEmpty())
    }

    private fun game(appId: Long, name: String, isGoal: Boolean = false) = Game(
        appId = appId,
        name = name,
        iconUrl = "",
        playtimeForever = 120,
        playtime2Weeks = 0,
        lastPlaytime = 120,
        isGoal = isGoal,
    )

    private fun session(appId: Long, startAt: Long, minutes: Int) = Session(
        appId = appId,
        startAt = startAt,
        endAt = startAt + minutes * 60_000L,
        minutes = minutes,
        open = false,
    )

    private fun achievement(appId: Long, apiName: String) = Achievement(
        appId = appId,
        apiName = apiName,
        displayName = apiName,
        unlocked = true,
        unlockedAt = 1_000L,
        snapshotPercent = 12.5,
        fetchedAt = 0L,
    )
}
