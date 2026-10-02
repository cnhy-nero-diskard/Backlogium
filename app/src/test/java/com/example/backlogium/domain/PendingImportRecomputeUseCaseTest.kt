package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.history.DataStoreHistoryImportRequestStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * Startup recovery for interrupted raw commits (stabilize-first-run-setup, tasks 5.5/5.6): the
 * Room pending marker resolves with BACKFILL (explicit import) vs RESTORE (backup) provenance,
 * never recomputes a marker owned by a previous account, and replays a recorded explicit request
 * that died before launch through the shared coordinator.
 */
@RunWith(RobolectricTestRunner::class)
class PendingImportRecomputeUseCaseTest {

    private val steamId = "76561198000000001"

    @Before
    fun clearSharedRequestStoreAndSettings() {
        // Shared-process isolation: clear the durable request bookkeeping AND the real
        // SettingsDataStore's cloud-refiling state + rule config so no sibling cloud-guard fixture
        // can leave reset() blocked or skew XP assertions.
        runBlocking {
            DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()).clearAll()
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            settings.clearCloudPresenceRefiling()
            settings.setRuleConfig(RuleConfig())
        }
    }

    private class Gateway(
        private val active: String?,
        private val pendingReset: String? = null,
    ) : HistoryImportAccountGateway {
        override suspend fun activeSteamId(): String? = active
        override suspend fun pendingResetSteamId(): String? = pendingReset
    }

    /**
     * Per-test-managed application scope for the coordinator: admission jobs are cancelled/joined
     * at teardown so nothing leaks into the next test; failures surface through the typed deferreds.
     */
    private val managedAppScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The most recent in-memory Room database built by [harness], closed at teardown. */
    private var openRoom: BacklogiumDatabase? = null

    @org.junit.After
    fun tearDownManagedScope() {
        runBlocking { managedAppScope.coroutineContext[Job]!!.cancelAndJoin() }
        openRoom?.close()
        openRoom = null
    }

    private class Harness(
        val database: BacklogiumDatabase,
        val settings: SettingsDataStore,
        val marks: InMemoryProgressMarksStore,
        val useCase: PendingImportRecomputeUseCase,
    )

    private fun harness(active: String? = steamId): Harness {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        openRoom = database
        val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, lastQuestCelebratedDate = null, initialized = true),
        )
        val updater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            progressMarksStore = marks,
        )
        val derived = DerivedStateWriteCoordinator()
        val backfill = PlaytimeBackfillUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            playerProfileDao = database.playerProfileDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            derivedStateWrites = derived,
            transaction = com.example.backlogium.data.backup.RoomDatabaseTransactionScope(database),
            syncCoordinator = SteamSyncCoordinator(),
            account = Gateway(active),
        )
        val coordinator = HistoryImportCoordinator(
            requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()),
            backfill = backfill,
            account = Gateway(active),
            time = FixedTime,
            appScope = managedAppScope,
        )
        val useCase = PendingImportRecomputeUseCase(
            playerProfileDao = database.playerProfileDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            derivedStateWrites = derived,
            syncCoordinator = SteamSyncCoordinator(),
            account = Gateway(active),
            coordinator = coordinator,
        )
        return Harness(database, settings, marks, useCase)
    }

    private fun ownedGame(appId: Long, forever: Int) = Game(
        appId = appId,
        name = "Game $appId",
        iconUrl = "",
        playtimeForever = forever,
        playtime2Weeks = 0,
        lastPlaytime = forever,
        backfillMinutes = forever,
    )

    // --- 5.5/5.6: BACKFILL marker resolves silently ----------------------------------------

    @Test
    fun backfillMarkerResumesWithBackfillProvenanceAndClearsSilently() = runTest {
        val h = harness()
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = steamId,
                playtimeBackfilled = true,
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = steamId,
                pendingImportRecomputeRequestId = "req-1",
            ),
        )
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 300))

        h.useCase()

        val profile = h.database.playerProfileDao().get()!!
        assertFalse(profile.pendingImportRecompute)
        assertNull(profile.pendingImportRecomputeSource)
        // The imported 300 minutes were already committed and are recounted from committed raw
        // state; 300 flat XP -> level 3 as the reseeded baseline, with no earned quest pending.
        assertEquals(300L, profile.totalXp)
        assertEquals(3, profile.level)
        val marks = h.marks.read()
        assertEquals(3, marks.lastCelebratedLevel)
        assertTrue(marks.pendingQuestDates.isEmpty())
    }

    @Test
    fun legacyNullProvenanceResolvesAsRestore() = runTest {
        val h = harness()
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = steamId,
                pendingImportRecompute = true,
                pendingImportRecomputeSource = null,
                pendingImportRecomputeSteamId = null,
            ),
        )
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

        h.useCase()

        val profile = h.database.playerProfileDao().get()!!
        assertFalse(profile.pendingImportRecompute)
        assertEquals(100L, profile.totalXp)
    }

    @Test
    fun markerOwnedByAnotherAccountIsNotResolvedForTheReplacementAccount() = runTest {
        val h = harness(active = steamId)
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = "other-account",
                playtimeBackfilled = true,
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = "old-account",
            ),
        )
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

        h.useCase()

        // The old account's marker stays; it is the account reset's to clear, never a recompute
        // run over the replacement account.
        assertTrue(h.database.playerProfileDao().get()!!.pendingImportRecompute)
    }

    @Test
    fun markerIsNotResolvedWhileAnAccountResetIsInFlight() = runTest {
        val h = harness(active = steamId)
        // Gateway reports a pending reset marker.
        val pending = PendingImportRecomputeUseCase(
            playerProfileDao = h.database.playerProfileDao(),
            settings = h.settings,
            gamificationUpdater = GamificationUpdater(
                sessionDao = h.database.sessionDao(),
                dailyProgressDao = h.database.dailyProgressDao(),
                playerProfileDao = h.database.playerProfileDao(),
                hltbDataDao = h.database.hltbDataDao(),
                achievementDao = h.database.achievementDao(),
                gameDao = h.database.gameDao(),
                hiddenGameDao = h.database.hiddenGameDao(),
                progressMarksStore = h.marks,
            ),
            time = FixedTime,
            derivedStateWrites = DerivedStateWriteCoordinator(),
            syncCoordinator = SteamSyncCoordinator(),
            account = Gateway(steamId, pendingReset = steamId),
            coordinator = HistoryImportCoordinator(
                requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()),
                backfill = com.example.backlogium.domain.PlaytimeBackfillUseCase(
                    gameDao = h.database.gameDao(),
                    sessionDao = h.database.sessionDao(),
                    playerProfileDao = h.database.playerProfileDao(),
                    settings = h.settings,
                    gamificationUpdater = GamificationUpdater(
                        sessionDao = h.database.sessionDao(),
                        dailyProgressDao = h.database.dailyProgressDao(),
                        playerProfileDao = h.database.playerProfileDao(),
                        hltbDataDao = h.database.hltbDataDao(),
                        achievementDao = h.database.achievementDao(),
                        gameDao = h.database.gameDao(),
                        hiddenGameDao = h.database.hiddenGameDao(),
                        progressMarksStore = InMemoryProgressMarksStore(),
                    ),
                    time = FixedTime,
                    derivedStateWrites = DerivedStateWriteCoordinator(),
                    account = Gateway(steamId, pendingReset = steamId),
                ),
                account = Gateway(steamId, pendingReset = steamId),
                time = FixedTime,
                appScope = managedAppScope,
            ),
        )
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = steamId,
                playtimeBackfilled = true,
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = steamId,
            ),
        )

        pending()

        assertTrue(h.database.playerProfileDao().get()!!.pendingImportRecompute)
    }

    // --- 5.5: replay of a pre-launch consent ------------------------------------------------

    @Test
    fun noMarkerReplaysARecordedExplicitRequestThroughTheCoordinator() = runTest {
        val h = harness()
        h.database.playerProfileDao().upsert(
            PlayerProfile(steamId = steamId, confirmedLibrarySteamId = steamId, confirmedLibraryAt = 1L),
        )
        h.database.gameDao().upsert(
            Game(
                appId = 440L, name = "Game 440", iconUrl = "",
                playtimeForever = 100, playtime2Weeks = 0, lastPlaytime = 100,
            ),
        )
        val store = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())
        store.recordExplicitRequest(steamId, 1L, "req-killed-before-launch")

        h.useCase()

        val profile = h.database.playerProfileDao().get()!!
        assertTrue(profile.playtimeBackfilled)
        assertEquals(100, h.database.gameDao().getById(440L)?.backfillMinutes)
        assertNull(store.request())
    }

    @Test
    fun noMarkerAndNoRequestIsANoOp() = runTest {
        val h = harness()
        h.useCase()
        assertNull(h.database.playerProfileDao().get())
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = Instant.parse("2026-07-26T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 7, 26)
    }
}