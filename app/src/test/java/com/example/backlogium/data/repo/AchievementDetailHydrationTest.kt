package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.achievement.AchievementObservationStore
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.*
import com.example.backlogium.domain.*
import com.example.backlogium.work.SteamSyncCoordinator
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementDetailHydrationTest {
    private lateinit var db: BacklogiumDatabase
    private lateinit var repo: AchievementRepository
    private var schema: List<AchievementSchemaDto>? = listOf(AchievementSchemaDto("first", "Objective", hidden = 1))
    private var player = PlayerAchievementsResult(success = false)
    private var failSchema = false
    private val requestedGames = mutableListOf<Long>()
    private var playerCalls = 0
    private var online = true

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().resetForAccountChange("fixture")
        // Hundreds of unplayed games must not turn one inspected detail into a sweep.
        (1L..300L).forEach { db.gameDao().upsert(Game(it, "Fixture $it", "", 0, 0, 0)) }
        val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, method, args ->
            when (method.name) {
                "getSchemaForGame" -> {
                    requestedGames += args[1] as Long
                    if (failSchema) throw IOException("fixture failure")
                    GameSchemaResponse(GameSchemaResult(schema?.let { AvailableGameStatsDto(it) }))
                }
                "getPlayerAchievements" -> { playerCalls++; PlayerAchievementsResponse(player) }
                "getGlobalAchievementPercentages" -> GlobalAchievementPercentagesResponse()
                else -> error("Unexpected ${method.name}")
            }
        } as SteamApi
        val time = SystemTimeProvider()
        repo = AchievementRepository(api, db.achievementDao(), db.gameAchievementSyncDao(), db.gameDao(),
            HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time), time,
            AchievementObservationStore(db, object : CredentialsProvider {
                override suspend fun currentCredentials() = CredentialsState.Configured("fixture", "fixture")
            }, AccountChangeMarkerStore(context), SteamSyncCoordinator()),
            object : com.example.backlogium.data.achievement.AchievementNetwork(context) {
                override fun isOnline() = online
            })
    }

    @After fun close() = db.close()

    @Test fun offlineOpeningDoesNotRequestAndCachedObjectivesRemainBrowsable() = runBlocking {
        online = false
        assertEquals(AchievementHydrationState.Unavailable(offline = true), repo.hydrateDetail(1).first())
        assertTrue(requestedGames.isEmpty())
        online = true
        repo.hydrateDetail(1).toList()
        online = false
        assertEquals(AchievementHydrationState.Ready, repo.hydrateDetail(1).first())
        assertEquals(1, repo.observeForGame(1).first().size)
        assertEquals(listOf(1L), requestedGames)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun failureRetriesAfterSixtySecondsAndStopsAfterSuccess() = kotlinx.coroutines.test.runTest {
        failSchema = true
        val states = mutableListOf<AchievementHydrationState>()
        repo.hydrateDetail(1).collect {
            states += it
            if (it is AchievementHydrationState.Unavailable) failSchema = false
        }
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(listOf(1L, 1L), requestedGames)
        assertEquals(AchievementHydrationState.Ready, states.last())
    }

    @Test fun privatePlayerStillCachesOneUnplayedCatalogueWithoutCompletionOrXp() = runBlocking {
        assertEquals(AchievementHydrationState.Ready, repo.hydrateDetail(1).toList().last())
        val row = repo.observeForGame(1).first().single()
        assertFalse(row.progressKnown)
        assertFalse(row.unlocked)
        assertTrue(row.hidden)
        assertNull(row.rarityPercent)
        assertTrue(repo.counts.first().isEmpty())
        assertTrue(repo.smartCollectionSignals.first().isEmpty())
        assertTrue(db.achievementWatchDao().getAll("fixture").isEmpty())
        repo.hydrateDetail(1).toList()
        assertEquals(listOf(1L), requestedGames)
        assertEquals(1, playerCalls)
        assertEquals(AchievementProgressAvailability.UNKNOWN, repo.observeCatalogue(1).first().progress)
    }

    @Test fun confirmedEmptyIsCachedButMalformedAndTransportFailuresRemainUnknown() = runBlocking {
        schema = emptyList()
        repo.hydrateDetail(1).toList()
        assertTrue(repo.observeCatalogue(1).first().confirmedEmpty)
        repo.hydrateDetail(1).toList()
        assertEquals(listOf(1L), requestedGames)
        schema = null
        assertTrue(repo.hydrateDetail(2).take(2).toList().last() is AchievementHydrationState.Unavailable)
        assertNull(db.achievementCatalogueDao().cache(2))
        failSchema = true
        repo.hydrateDetail(3).take(2).toList()
        assertNull(db.achievementCatalogueDao().cache(3))
    }

    @Test fun contradictoryEmptyPlayerPreservesCatalogueThenAuthoritativePlayerMergesWithoutAlert() = runBlocking {
        player = PlayerAchievementsResult(success = true)
        repo.hydrateDetail(1).toList()
        assertNull(db.gameAchievementSyncDao().get(1))
        assertEquals(1, db.achievementCatalogueDao().entries(1).size)
        // Stale the catalogue to authorize a later detail hydration, without forcing all games.
        db.achievementCatalogueDao().upsert(com.example.backlogium.data.local.entity.AchievementCatalogueCache(1, 0))
        player = PlayerAchievementsResult(true, achievements = listOf(PlayerAchievementDto("first", 1, 5)))
        repo.hydrateDetail(1).toList()
        assertTrue(repo.observeForGame(1).first().single().progressKnown)
        assertTrue(db.achievementDao().getOne(1, "first")!!.unlocked)
        val work = db.achievementWatchDao().getAll("fixture").single()
        assertNull(work.payload)
        assertTrue(work.recomputeNeeded)
        assertEquals("XP_INTEGRITY_CORRECTION", work.recomputeSource)
    }
}
