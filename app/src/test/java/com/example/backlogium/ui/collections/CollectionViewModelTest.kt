package com.example.backlogium.ui.collections

import androidx.lifecycle.SavedStateHandle
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.HltbDirectLookupResult
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.GameGenreRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.fakeHiddenGamesRepository
import com.example.backlogium.data.repo.HltbDatasetLookup
import com.example.backlogium.data.repo.HltbRepository
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.PersonalPaceRepository
import com.example.backlogium.data.repo.PlaySessionEndPublisher
import com.example.backlogium.data.repo.PresenceObserver
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.SteamStoreGenreDataSource
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.FakeHiddenGameDao
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.TimeProvider
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CollectionViewModelTest {

    private lateinit var store: RecordingCollectionStore

    @Before
    fun setUp() {
        store = RecordingCollectionStore()
    }

    @Test
    fun blankOrWhitespaceSave_doesNotWriteOrNavigate() = withViewModel { viewModel ->
        viewModel.save()
        runCurrent()

        viewModel.setName(String(charArrayOf(' ', 9.toChar(), ' ')))
        viewModel.save()
        runCurrent()

        assertTrue(store.collections.isEmpty())
        assertFalse(viewModel.uiState.value.done)
    }

    @Test
    fun nonBlankSave_persistsAndNavigates() = withViewModel { viewModel ->
        viewModel.setName("Keep this")
        viewModel.addGame(42L)
        viewModel.save()
        runCurrent()

        val stored = store.collections.single()
        assertEquals("Keep this", stored.name)
        assertEquals(listOf(42L), store.members.filter { it.collectionId == stored.id }.map { it.appId })
        assertTrue(viewModel.uiState.value.done)
    }

    /**
     * Hiding from this screen's own game-detail sheet has to take effect immediately. The member
     * buffer is a snapshot taken when the screen opened, and the library it renders against is
     * filtered — so a member hidden mid-session used to survive as a row whose game had vanished,
     * which is exactly how a genuinely dangling member looks, and it rendered as one: "Game 2",
     * no playtime, no sessions, and nothing to navigate to.
     */
    @Test
    fun hidingAMemberMidSession_dropsItFromTheRenderedMembers() {
        val hiddenGames = fakeHiddenGamesRepository()
        withViewModel(
            libraryGames = listOf(
                Game(
                    appId = 1L,
                    name = "Alpha",
                    iconUrl = "",
                    playtimeForever = 20,
                    playtime2Weeks = 0,
                    lastPlaytime = 20,
                ),
                Game(
                    appId = 2L,
                    name = "Beta",
                    iconUrl = "",
                    playtimeForever = 80,
                    playtime2Weeks = 0,
                    lastPlaytime = 80,
                ),
            ),
            hiddenGames = hiddenGames,
        ) { viewModel ->
            viewModel.addGame(1L)
            viewModel.addGame(2L)
            runCurrent()
            assertEquals(listOf("Alpha", "Beta"), viewModel.uiState.value.members.map { it.name })

            hiddenGames.hide(setOf(2L))
            runCurrent()

            val members = viewModel.uiState.value.members
            assertEquals(listOf("Alpha"), members.map { it.name })
            assertTrue(members.none { it.name == "Game 2" })
        }
    }

    @Test
    fun nonQueueMembers_renderInSelectedSortOrder() = withViewModel(
        libraryGames = listOf(
            Game(
                appId = 1L,
                name = "Alpha",
                iconUrl = "",
                playtimeForever = 20,
                playtime2Weeks = 0,
                lastPlaytime = 20,
            ),
            Game(
                appId = 2L,
                name = "Zulu",
                iconUrl = "",
                playtimeForever = 80,
                playtime2Weeks = 0,
                lastPlaytime = 80,
            ),
        ),
        hltbData = listOf(
            HltbData(
                appId = 1L,
                completionistMinutes = 100,
                fetchedAt = 1L,
                matchStatus = HltbMatchStatus.RESOLVED,
            ),
            HltbData(
                appId = 2L,
                completionistMinutes = 100,
                fetchedAt = 1L,
                matchStatus = HltbMatchStatus.RESOLVED,
            ),
        ),
    ) { viewModel ->
        viewModel.setMode(CollectionMode.DEADLINE_GOAL)
        viewModel.addGame(1L)
        viewModel.addGame(2L)
        runCurrent()

        assertEquals(listOf("Zulu", "Alpha"), viewModel.uiState.value.members.map { it.name })
    }

    @Test
    fun failedSave_releasesBusyStateAndCanBeRetried() {
        val transaction = FailOnceTransactionScope()
        withViewModel(transaction) { viewModel ->
            viewModel.setName("Retry me")
            viewModel.save()
            runCurrent()

            assertFalse(viewModel.uiState.value.saving)
            assertFalse(viewModel.uiState.value.done)

            viewModel.save()
            runCurrent()

            assertEquals(2, transaction.calls.get())
            assertTrue(viewModel.uiState.value.done)
            assertEquals("Retry me", store.collections.single().name)
        }
    }

    @Test
    fun cancelPath_withoutSave_doesNotPersistBufferedChanges() = withViewModel { viewModel ->
        viewModel.setName("Discarded")
        viewModel.addGame(99L)
        runCurrent()

        assertTrue(store.collections.isEmpty())
    }

    private fun withViewModel(
        transaction: DatabaseTransactionScope = PassThroughTransactionScope,
        libraryGames: List<Game> = emptyList(),
        hltbData: List<HltbData> = emptyList(),
        hiddenGames: HiddenGamesRepository = fakeHiddenGamesRepository(),
        block: suspend TestScope.(CollectionViewModel) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(transaction, libraryGames, hltbData, hiddenGames)
            val stateCollector = launch {
                viewModel.uiState.collect()
            }
            runCurrent()
            block(viewModel)
            stateCollector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        transaction: DatabaseTransactionScope,
        libraryGames: List<Game>,
        hltbData: List<HltbData>,
        hiddenGames: HiddenGamesRepository = fakeHiddenGamesRepository(),
    ): CollectionViewModel {
        val time = FixedTimeProvider()
        val gameDao = emptyGameDao(libraryGames)
        val sessionRepository = SessionRepository(
            emptySessionDao(),
            hiddenGamesRepository = fakeHiddenGamesRepository(),
        )
        val hltbRepository = HltbRepository(
            dataSource = object : HltbDataSource {
                override suspend fun search(name: String) = emptyList<com.example.backlogium.data.hltb.HltbCandidate>()
                override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult =
                    HltbDirectLookupResult.NotFound
            },
            hltbDataDao = emptyHltbDataDao(hltbData),
            datasetLookup = HltbDatasetLookup { null },
            hiddenGameDao = FakeHiddenGameDao(),
            json = Json,
            time = time,
        )
        val gameGenreRepository = GameGenreRepository(
            cacheDao = emptyGameGenreCacheDao(),
            store = SteamStoreGenreDataSource(emptyProxy(SteamStoreApi::class.java)),
            time = time,
        )
        val gameRepository = GameRepository(
            gameDao = gameDao,
            hltbRepository = hltbRepository,
            gameGenreRepository = gameGenreRepository,
            hiddenGamesRepository = hiddenGames,
            steamApi = emptyProxy(SteamApi::class.java),
            sessionRepository = sessionRepository,
            time = time,
        )
        val achievementRepository = AchievementRepository(
            steamApi = emptyProxy(SteamApi::class.java),
            achievementDao = emptyAchievementDao(),
            gameAchievementSyncDao = emptyGameAchievementSyncDao(),
            gameDao = gameDao,
            hiddenGamesRepository = fakeHiddenGamesRepository(),
            time = time,
        )
        val settings = emptySettingsRepository()
        val liveStatusRepository = LiveStatusRepository(
            steamApi = emptyProxy(SteamApi::class.java),
            gameDao = gameDao,
            hiddenGameDao = FakeHiddenGameDao(),
            profileDao = emptyProxy(PlayerProfileDao::class.java),
            credentials = object : CredentialsProvider {
                override suspend fun currentCredentials() = null
            },
            settings = settings,
            time = time,
            sessionEnds = PlaySessionEndPublisher(),
            presenceObserver = PresenceObserver { _, _ -> },
            diagnostics = null,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        return CollectionViewModel(
            savedStateHandle = SavedStateHandle(),
            collectionRepository = CollectionRepository(
                collectionDao = store.dao,
                hiddenGamesRepository = fakeHiddenGamesRepository(),
                time = time,
                transaction = transaction,
            ),
            gameRepository = gameRepository,
            hiddenGamesRepository = hiddenGames,
            achievementRepository = achievementRepository,
            sessionRepository = sessionRepository,
            personalPaceRepository = PersonalPaceRepository(sessionRepository, time),
            settings = settings,
            liveStatusRepository = liveStatusRepository,
            currentDate = com.example.backlogium.domain.CurrentDateProvider(time),
        )
    }

    private class FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 100L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.of(2026, 9, 8)
    }

    private class FailOnceTransactionScope : DatabaseTransactionScope {
        val calls = AtomicInteger()

        override suspend fun <R> run(block: suspend () -> R): R {
            if (calls.incrementAndGet() == 1) {
                error("injected collection save failure")
            }
            return block()
        }
    }

    private class RecordingCollectionStore {
        private val collectionRows = mutableListOf<Collection>()
        private val memberRows = mutableListOf<CollectionMember>()
        private val collectionFlow = MutableStateFlow<List<Collection>>(emptyList())
        private val memberFlow = MutableStateFlow<List<CollectionMember>>(emptyList())
        private var nextId = 1L

        val collections: List<Collection> get() = collectionRows.toList()
        val members: List<CollectionMember> get() = memberRows.toList()

        val dao: CollectionDao = Proxy.newProxyInstance(
            CollectionDao::class.java.classLoader,
            arrayOf(CollectionDao::class.java),
        ) { _, method, args ->
            val values = args ?: emptyArray()
            when (method.name) {
                "observeCollections" -> collectionFlow
                "observeAllMembers", "observeMembers" -> memberFlow
                "getAll" -> collectionRows.toList()
                "getById" -> collectionRows.firstOrNull { it.id == values[0] as Long }
                "insert" -> {
                    val row = (values[0] as Collection).copy(id = nextId++)
                    collectionRows += row
                    publish()
                    row.id
                }
                "updateDetails" -> {
                    val id = values[0] as Long
                    val index = collectionRows.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        collectionRows[index] = collectionRows[index].copy(
                            name = values[1] as String,
                            mode = values[2] as com.example.backlogium.domain.CollectionMode,
                            sort = values[3] as com.example.backlogium.domain.CollectionSort,
                            targetDate = values[4] as String?,
                            accent = values[5] as com.example.backlogium.domain.CollectionAccent?,
                            timeBasis = values[6] as com.example.backlogium.domain.CollectionTimeBasis,
                            description = values[7] as String?,
                        )
                        publish()
                    }
                    Unit
                }
                "getMembers" -> memberRows.filter { it.collectionId == values[0] as Long }
                    .sortedBy { it.orderIndex }
                "insertMember" -> {
                    val member = values[0] as CollectionMember
                    if (memberRows.none { it.collectionId == member.collectionId && it.appId == member.appId }) {
                        memberRows += member
                        publish()
                    }
                    Unit
                }
                "setOrderIndex" -> {
                    val collectionId = values[0] as Long
                    val appId = values[1] as Long
                    val index = memberRows.indexOfFirst {
                        it.collectionId == collectionId && it.appId == appId
                    }
                    if (index >= 0) {
                        memberRows[index] = memberRows[index].copy(orderIndex = values[2] as Int)
                        publish()
                    }
                    Unit
                }
                "removeMember" -> {
                    memberRows.removeAll {
                        it.collectionId == values[0] as Long && it.appId == values[1] as Long
                    }
                    publish()
                    Unit
                }
                "setMemberDone" -> {
                    val collectionId = values[0] as Long
                    val appId = values[1] as Long
                    val index = memberRows.indexOfFirst {
                        it.collectionId == collectionId && it.appId == appId
                    }
                    if (index >= 0) {
                        memberRows[index] = memberRows[index].copy(done = values[2] as Boolean)
                        publish()
                    }
                    Unit
                }
                else -> null
            }
        } as CollectionDao

        private fun publish() {
            collectionFlow.value = collectionRows.toList()
            memberFlow.value = memberRows.sortedWith(compareBy({ it.collectionId }, { it.orderIndex }))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: (String) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            handler(method.name)
        } as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> emptyProxy(type: Class<T>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null } as T

    private fun emptyGameDao(libraryGames: List<Game>): GameDao = proxy(GameDao::class.java) { method ->
        when (method) {
            "observeLibrary" -> flowOf(libraryGames)
            "observeGoalGames", "observeBacklog" -> flowOf(emptyList<Game>())
            else -> null
        }
    }

    private fun emptySessionDao(): SessionDao = proxy(SessionDao::class.java) { method ->
        when (method) {
            "observeEarliestSessionStart" -> flowOf(null)
            "observeTrackedMinutesByGame",
            "observeFirstSessionStartByGame",
            "observeLatestSessionInstantByGame",
            "observeSessionCountsByGame",
            "observeClosedSince",
            -> flowOf(emptyList<Any>())
            else -> null
        }
    }

    private fun emptyHltbDataDao(hltbData: List<HltbData>): HltbDataDao =
        proxy(HltbDataDao::class.java) { method ->
        when (method) {
            "observeNeedsReview", "observeMatchCenter" -> flowOf(emptyList<HltbData>())
            "observeAllWithDataset" -> flowOf(hltbData)
            else -> null
        }
    }

    private fun emptyGameGenreCacheDao(): GameGenreCacheDao =
        proxy(GameGenreCacheDao::class.java) { method ->
            when (method) {
                "observeAll" -> flowOf(emptyList<Any>())
                else -> null
            }
        }

    private fun emptyAchievementDao(): AchievementDao = proxy(AchievementDao::class.java) { method ->
        when (method) {
            "observeCounts", "observeUnlockedRarity" -> flowOf(emptyList<Any>())
            else -> null
        }
    }

    private fun emptyGameAchievementSyncDao(): GameAchievementSyncDao =
        proxy(GameAchievementSyncDao::class.java) { method ->
            when (method) {
                "observeAll" -> flowOf(emptyList<Any>())
                else -> null
            }
        }

    private fun emptySettingsRepository(): SettingsRepository =
        proxy(SettingsRepository::class.java) { method ->
            when (method) {
                "getCollectionDensity" -> flowOf(GameListDensity.LIST)
                "getLiveSession" -> flowOf(LiveSessionState())
                else -> null
            }
        }
}
