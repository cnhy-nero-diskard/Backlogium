package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.history.HistoryImportRequestRecord
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Repository-facing tests for the tightened one-time history import and its reset
 * (stabilize-first-run-setup, tasks 5.1-5.4 and 5.7) against a real Room database and DataStore:
 * atomic snapshot/flag/marker commit, baseline gating, idempotence, preservation of converted
 * credits / manual / hidden evidence, and the reset/cloud-transfer safeguards.
 */
@RunWith(RobolectricTestRunner::class)
class PlaytimeBackfillUseCaseImportTest {

    private val steamId = "76561198000000001"

    /** The most recent in-memory Room database built by [harness], closed at teardown. */
    private var openRoom: BacklogiumDatabase? = null

    @Before
    fun baselineSharedSettingsAndTrackRoom() {
        // Shared-process isolation: a prior cloud-guard fixture may have left the refiling applied
        // flag/receipt in the single real SettingsDataStore, and a modified RuleConfig would skew XP
        // assertions. Restore a clean baseline WITHOUT weakening the cloud-guard test itself (it
        // explicitly sets the flags again and still asserts BLOCKED).
        runBlocking {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            settings.clearCloudPresenceRefiling()
            settings.setRuleConfig(RuleConfig())
        }
    }

    @After
    fun closeTrackedRoom() {
        openRoom?.close()
        openRoom = null
    }

    private class Gateway(
        private val active: String?,
        private val pendingReset: String?,
    ) : HistoryImportAccountGateway {
        override suspend fun activeSteamId(): String? = active
        override suspend fun pendingResetSteamId(): String? = pendingReset
    }

    /**
     * Blocks the durable-marker read on its second call — the derived-boundary recheck — so a test
     * can flip the account-change marker while an import/reset waits for the derived mutex.
     */
    private class FlipMarkerGateway(
        private val activeAccount: String,
    ) : HistoryImportAccountGateway {
        var pending: String? = null
        val recheckEntered = CompletableDeferred<Unit>()
        val recheckRelease = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun activeSteamId(): String? = activeAccount

        override suspend fun pendingResetSteamId(): String? {
            calls += 1
            if (calls == 2) {
                recheckEntered.complete(Unit)
                recheckRelease.await()
            }
            return pending
        }
    }

    /**
     * Delegates every profile DAO call to the real Room DAO except the final derived write, which
     * throws for the first [failuresRemaining] calls — simulating a crash between the raw commit
     * and the recomputation's finalize that a subsequent retry recovers from.
     */
    private class FailOnUpdateGamificationDao(
        private val delegate: com.example.backlogium.data.local.dao.PlayerProfileDao,
        private var failuresRemaining: Int = 1,
    ) : com.example.backlogium.data.local.dao.PlayerProfileDao by delegate {
        override suspend fun updateGamification(
            totalXp: Long,
            level: Int,
            currentStreak: Int,
            longestStreak: Int,
            gamificationConfigVersion: Long,
        ) {
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IllegalStateException("derived write interrupted")
            }
            delegate.updateGamification(
                totalXp,
                level,
                currentStreak,
                longestStreak,
                gamificationConfigVersion,
            )
        }
    }

    private class Harness(
        val database: BacklogiumDatabase,
        val settings: SettingsDataStore,
        val marks: InMemoryProgressMarksStore,
        val useCase: PlaytimeBackfillUseCase,
    )

    private fun request(account: String = steamId, requestId: String = "req-1") =
        HistoryImportRequestRecord(steamId = account, requestedAt = 1000L, requestId = requestId)

    private fun harness(
        active: String? = steamId,
        pendingReset: String? = null,
        transaction: DatabaseTransactionScope? = null,
    ): Harness {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        openRoom = database
        val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
        val marks = InMemoryProgressMarksStore()
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
        val useCase = PlaytimeBackfillUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            playerProfileDao = database.playerProfileDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = transaction ?: RoomDatabaseTransactionScope(database),
            syncCoordinator = SteamSyncCoordinator(),
            account = Gateway(active, pendingReset),
        )
        return Harness(database, settings, marks, useCase)
    }

    private suspend fun Harness.seedConfirmedProfile(account: String = steamId) {
        database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = account,
                confirmedLibrarySteamId = account,
                confirmedLibraryAt = 1234L,
            ),
        )
    }

    private fun ownedGame(
        appId: Long,
        forever: Int,
        backfill: Int = 0,
        manualShared: Int = 0,
    ) = Game(
        appId = appId,
        name = "Game $appId",
        iconUrl = "",
        playtimeForever = forever,
        playtime2Weeks = 0,
        lastPlaytime = forever,
        backfillMinutes = backfill,
        manualSharedMinutes = manualShared,
    )

    private suspend fun Harness.track(appId: Long, minutes: Int) {
        database.sessionDao().insert(
            Session(
                appId = appId,
                startAt = utc("2026-07-01T10:00:00Z"),
                endAt = utc("2026-07-01T10:00:00Z") + minutes * 60_000L,
                minutes = minutes,
                open = false,
            ),
        )
    }

    // --- 5.1 / 5.2: baseline gating and shared result states -------------------------------

    @Test
    fun freshImportFreezesOffsetsFlagAndMarkerThenClearsOnSuccessfulRecompute() = runTest {
        val h = harness()
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))
        h.database.gameDao().upsert(ownedGame(appId = 730L, forever = 50))
        h.track(appId = 440L, minutes = 30)

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.Imported(creditedGames = 2, creditedMinutes = 120), result)
        assertEquals(70, h.database.gameDao().getById(440L)?.backfillMinutes)
        assertEquals(50, h.database.gameDao().getById(730L)?.backfillMinutes)
        val profile = h.database.playerProfileDao().get()!!
        assertTrue(profile.playtimeBackfilled)
        // The administrative recompute finalized inside the call and cleared the marker.
        assertFalse(profile.pendingImportRecompute)
        assertNull(profile.pendingImportRecomputeSource)
        // 100 + 50 cumulative minutes, flat fallback -> 150 XP.
        assertEquals(150L, profile.totalXp)
    }

    @Test
    fun confirmedEmptyLibraryImportsZeroChanges() = runTest {
        val h = harness()
        h.seedConfirmedProfile()

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.Imported(creditedGames = 0, creditedMinutes = 0), result)
        val profile = h.database.playerProfileDao().get()!!
        assertTrue(profile.playtimeBackfilled)
        assertFalse(profile.pendingImportRecompute)
    }

    @Test
    fun noBaselineWritesNothing() = runTest {
        val h = harness()
        // Profile exists but has no same-account confirmation evidence.
        h.database.playerProfileDao().upsert(PlayerProfile(steamId = steamId))
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.NeedsBaseline, result)
        val profile = h.database.playerProfileDao().get()!!
        assertFalse(profile.playtimeBackfilled)
        assertEquals(0, h.database.gameDao().getById(440L)?.backfillMinutes)
        assertFalse(profile.pendingImportRecompute)
    }

    @Test
    fun baselineForAnotherAccountIsNotReadiness() = runTest {
        // The ACTIVE profile belongs to the request account, but its only confirmation evidence
        // names a DIFFERENT account: confirmation is account-scoped evidence, so the import must
        // report NeedsBaseline (not Superseded — the account itself never mismatched).
        val h = harness()
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = steamId,
                confirmedLibrarySteamId = "other-account",
                confirmedLibraryAt = 1234L,
            ),
        )

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.NeedsBaseline, result)
        assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
    }

    @Test
    fun alreadyImportedIsANoOpWithNoAddedOffsets() = runTest {
        val h = harness()
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))
        h.useCase.importSteamHistory(request())
        val frozen = h.database.gameDao().getById(440L)!!.backfillMinutes

        val repeat = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.AlreadyImported, repeat)
        assertEquals(frozen, h.database.gameDao().getById(440L)!!.backfillMinutes)
    }

    @Test
    fun legacyCompletedImportWithoutBaselineStaysCompleted() = runTest {
        val h = harness()
        // Completed in a previous version: no confirmed-baseline evidence exists, and none is
        // required — downgrading to NeedsBaseline would revoke an earned completion.
        h.database.playerProfileDao().upsert(
            PlayerProfile(steamId = steamId, playtimeBackfilled = true),
        )

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.AlreadyImported, result)
    }

    @Test
    fun growingSteamTotalsAreNotReimported() = runTest {
        val h = harness()
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))
        h.useCase.importSteamHistory(request())
        // A later sync observes a higher lifetime total.
        h.database.gameDao().updateSteamFields(
            appId = 440L, name = "Game 440", iconUrl = "",
            playtimeForever = 400, playtime2Weeks = 0, lastPlaytime = 400,
            lastSyncedAt = 9000L, lastPlayedAt = null, returnedToPlayAt = null,
        )

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.AlreadyImported, result)
        assertEquals(100, h.database.gameDao().getById(440L)?.backfillMinutes)
    }

    // --- 5.2: superseded accounts ----------------------------------------------------------

    @Test
    fun requestForAnotherActiveAccountIsSuperseded() = runTest {
        val h = harness(active = "76561198000000002")
        h.seedConfirmedProfile(account = "76561198000000002")
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

        val result = h.useCase.importSteamHistory(request(account = steamId))

        assertEquals(HistoryImportResult.Superseded, result)
        assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
        assertEquals(0, h.database.gameDao().getById(440L)?.backfillMinutes)
    }

    @Test
    fun importDuringPendingAccountResetIsSuperseded() = runTest {
        val h = harness(pendingReset = steamId)
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.Superseded, result)
        assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
    }

    @Test
    fun storedLibraryOfAnotherAccountIsSuperseded() = runTest {
        val h = harness()
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = "other-account",
                confirmedLibrarySteamId = "other-account",
                confirmedLibraryAt = 1234L,
            ),
        )

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.Superseded, result)
    }

    // --- 5.3: atomic snapshot, rollback and concurrent coherence ---------------------------

    @Test
    fun failedRawCommitWritesNothingAndReturnsFailure() = runTest {
        var calls = 0
        val failing = object : DatabaseTransactionScope {
            override suspend fun <R> run(block: suspend () -> R): R {
                calls += 1
                if (calls == 1) throw IllegalStateException("raw commit failed")
                return block()
            }
        }
        val h = harness(transaction = failing)
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

        val result = h.useCase.importSteamHistory(request())

        assertTrue(result is HistoryImportResult.Failed)
        assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
        assertEquals(0, h.database.gameDao().getById(440L)?.backfillMinutes)
        assertFalse(h.database.playerProfileDao().get()!!.pendingImportRecompute)
    }

    @Test
    fun roomTransactionScopeRollsBackPartialRawWrites() = runTest {
        // The import's raw unit is one Room transaction: offsets, flag and marker commit together
        // or none do. Prove the seam itself rolls back a block that throws mid-way, exactly like an
        // interrupted kill after the first DAO call.
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))
            val scope = RoomDatabaseTransactionScope(database)
            runCatching {
                scope.run {
                    database.gameDao().setBackfillMinutes(440L, 100)
                    database.playerProfileDao().insertIfMissing()
                    database.playerProfileDao().updatePlaytimeBackfilled(true)
                    error("interrupted after partial write")
                }
            }
            val stored = database.gameDao().getById(440L)!!
            assertEquals(0, stored.backfillMinutes)
            val profile = database.playerProfileDao().get()
            assertTrue(profile == null || !profile.playtimeBackfilled)
        } finally {
            database.close()
        }
    }

    @Test
    fun concurrentImportAndSyncCommitProduceCoherentMinutes() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            val marks = InMemoryProgressMarksStore()
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
            val viaDatabase = PlaytimeBackfillUseCase(
                gameDao = database.gameDao(),
                sessionDao = database.sessionDao(),
                playerProfileDao = database.playerProfileDao(),
                settings = settings,
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(database),
                syncCoordinator = SteamSyncCoordinator(),
                account = Gateway(steamId, null),
            )
            database.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, confirmedLibrarySteamId = steamId, confirmedLibraryAt = 1L),
            )
            database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

            // A simulated sync and the import race on the same sync -> derived lock order. Either
            // order must yield the same coherent total: frozen offset computed from the snapshot at
            // import commit time, plus the sync's tracked minutes, each minute counted exactly once.
            val simulatedSync = async {
                database.sessionDao().insert(
                    Session(
                        appId = 440L,
                        startAt = utc("2026-07-02T10:00:00Z"),
                        endAt = utc("2026-07-02T10:30:00Z"),
                        minutes = 15,
                        open = false,
                    ),
                )
                database.gameDao().updateSteamFields(
                    appId = 440L, name = "Game 440", iconUrl = "",
                    playtimeForever = 115, playtime2Weeks = 0, lastPlaytime = 115,
                    lastSyncedAt = 2000L, lastPlayedAt = null, returnedToPlayAt = null,
                )
            }
            val import = async { viaDatabase.importSteamHistory(request()) }

            simulatedSync.await()
            val importResult = import.await()

            assertTrue(importResult == HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100))
            val game = database.gameDao().getById(440L)!!
            val tracked = database.sessionDao().trackedMinutesByGame().single().minutes
            assertEquals(100, game.backfillMinutes)
            assertEquals(15, tracked)
            // Coherent: imported + tracked equals the lifetime total at the sync's commit.
            assertEquals(115, game.backfillMinutes + tracked)
            assertTrue(database.playerProfileDao().get()!!.playtimeBackfilled)
        } finally {
            database.close()
        }
    }

    // --- 5.4: preservation of converted credits / manual / hidden / sessions ---------------

    @Test
    fun convertedSharedToOwnedCreditIsRaisedWithoutErasing() = runTest {
        val h = harness()
        h.seedConfirmedProfile()
        // 440 carried a folded manual estimate through shared->owned conversion (backfill=600).
        // The coherent formula is max(existing credit, lifetime - tracked) = max(600, 900-60) =
        // 840: the credit is never erased, and no freshly eligible historical minute is lost.
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 900, backfill = 600))
        h.database.gameDao().upsert(ownedGame(appId = 730L, forever = 50))
        h.track(appId = 440L, minutes = 60)

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.Imported(creditedGames = 2, creditedMinutes = 890), result)
        assertEquals(840, h.database.gameDao().getById(440L)?.backfillMinutes)
        assertEquals(50, h.database.gameDao().getById(730L)?.backfillMinutes)
        // Each minute counted once: imported-with-credit (840) + tracked (60) = lifetime (900).
    }

    @Test
    fun convertedCreditAndSnapshotCoalesceToTheCoherentMax() = runTest {
        // Dedicated fixture from the parent review: 100 converted credit, 1000 lifetime, 100
        // tracked. The snapshot computes 900, so the offset is exactly 900 — an existing credit
        // never lowers an eligible offset and nothing is erased or double-counted.
        val h = harness()
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 1000, backfill = 100))
        h.track(appId = 440L, minutes = 100)

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 900), result)
        assertEquals(900, h.database.gameDao().getById(440L)?.backfillMinutes)
    }

    @Test
    fun manualSharedAndHiddenEvidenceAreUntouched() = runTest {
        val h = harness()
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))
        h.database.gameDao().upsert(
            Game(
                appId = 999L,
                name = "Shared",
                iconUrl = "",
                playtimeForever = 0,
                playtime2Weeks = 0,
                lastPlaytime = 0,
                source = GameSource.FAMILY_SHARED,
                manualSharedMinutes = 300,
            ),
        )
        h.database.hiddenGameDao().upsertAll(
            listOf(com.example.backlogium.data.local.entity.HiddenGame(appId = 440L, hiddenAt = 5L)),
        )
        h.track(appId = 440L, minutes = 30)
        val sessionsBefore = h.database.sessionDao().getAll()

        h.useCase.importSteamHistory(request())

        val shared = h.database.gameDao().getById(999L)!!
        assertEquals(GameSource.FAMILY_SHARED, shared.source)
        // Manual estimate and shared rows are never touched by the import.
        assertEquals(300, shared.manualSharedMinutes)
        assertEquals(0, shared.backfillMinutes)
        // Hidden evidence intact.
        assertEquals(setOf(440L), h.database.hiddenGameDao().hiddenAppIds().toSet())
        // No dated session is invented from lifetime counters.
        assertEquals(sessionsBefore.size, h.database.sessionDao().getAll().size)
        assertEquals(30, h.database.sessionDao().trackedMinutesByGame().single().minutes)
    }

    // --- 5.5: interruption recovery never refreezes ----------------------------------------

    @Test
    fun pendingRecomputeResumeDoesNotRefreezeFromGrowingTotals() = runTest {
        val h = harness()
        // Raw commit landed (offsets 70 + flag) and the process died before recomputation
        // finalized: the marker is held with BACKFILL provenance for this account.
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = steamId,
                confirmedLibrarySteamId = steamId,
                confirmedLibraryAt = 1234L,
                playtimeBackfilled = true,
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = steamId,
                pendingImportRecomputeRequestId = "req-1",
            ),
        )
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100, backfill = 70))
        h.track(appId = 440L, minutes = 30)
        // A later sync observes a higher lifetime total while the recomputation is pending.
        h.database.gameDao().updateSteamFields(
            appId = 440L, name = "Game 440", iconUrl = "",
            playtimeForever = 400, playtime2Weeks = 0, lastPlaytime = 400,
            lastSyncedAt = 5000L, lastPlayedAt = null, returnedToPlayAt = null,
        )

        val result = h.useCase.importSteamHistory(request())

        // Resumed recomputation with the frozen offset intact — no recapture of the growing total.
        assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 70), result)
        assertEquals(70, h.database.gameDao().getById(440L)?.backfillMinutes)
        assertFalse(h.database.playerProfileDao().get()!!.pendingImportRecompute)
    }

    @Test
    fun pendingRecomputeForAnotherAccountIsSuperseded() = runTest {
        val h = harness(active = steamId)
        // The raw import committed for an OLD account (flag + marker held); this request is for the
        // active account and must not resume the old account's recomputation over it.
        h.database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = steamId,
                confirmedLibrarySteamId = steamId,
                confirmedLibraryAt = 1234L,
                playtimeBackfilled = true,
                pendingImportRecompute = true,
                pendingImportRecomputeSource = RecomputeSource.BACKFILL.name,
                pendingImportRecomputeSteamId = "old-account",
                pendingImportRecomputeRequestId = "req-old",
            ),
        )
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100, backfill = 70))

        val result = h.useCase.importSteamHistory(request())

        assertEquals(HistoryImportResult.Superseded, result)
        assertTrue(h.database.playerProfileDao().get()!!.pendingImportRecompute)
    }

    @Test
    fun failedRecomputeReportsPendingRecomputeAndKeepsMarker() = runTest {
        // The raw commit (offsets + flag + marker) must stay durable when the derived
        // recomputation cannot finalize, so a retry resumes instead of re-importing.
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            val failingProfileDao = FailOnUpdateGamificationDao(database.playerProfileDao())
            val updater = GamificationUpdater(
                sessionDao = database.sessionDao(),
                dailyProgressDao = database.dailyProgressDao(),
                playerProfileDao = failingProfileDao,
                hltbDataDao = database.hltbDataDao(),
                achievementDao = database.achievementDao(),
                gameDao = database.gameDao(),
                hiddenGameDao = database.hiddenGameDao(),
                progressMarksStore = InMemoryProgressMarksStore(),
            )
            val useCase = PlaytimeBackfillUseCase(
                gameDao = database.gameDao(),
                sessionDao = database.sessionDao(),
                playerProfileDao = failingProfileDao,
                settings = settings,
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(database),
                syncCoordinator = SteamSyncCoordinator(),
                account = Gateway(steamId, null),
            )
            database.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, confirmedLibrarySteamId = steamId, confirmedLibraryAt = 1L),
            )
            database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

            val result = useCase.importSteamHistory(request())

            assertEquals(HistoryImportResult.PendingRecompute, result)
            val stored = database.playerProfileDao().get()!!
            assertTrue(stored.playtimeBackfilled)
            assertTrue(stored.pendingImportRecompute)
            assertEquals(RecomputeSource.BACKFILL.name, stored.pendingImportRecomputeSource)
            assertEquals(steamId, stored.pendingImportRecomputeSteamId)
            assertEquals("req-1", stored.pendingImportRecomputeRequestId)
            assertEquals(100, database.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            database.close()
        }
    }

    // --- 5.7: reset safeguards -------------------------------------------------------------

    @Test
    fun resetClearsOffsetsAndFlagAndPreservesSessionsAndStreaks() = runTest {
        val h = harness()
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))
        h.track(appId = 440L, minutes = 30)
        h.useCase.importSteamHistory(request())
        val profileAfterImport = h.database.playerProfileDao().get()!!
        assertEquals(100L, profileAfterImport.totalXp)
        val sessionsBefore = h.database.sessionDao().getAll()
        val streakBefore = profileAfterImport.longestStreak

        val result = h.useCase.reset()

        assertEquals(PlaytimeBackfillResetResult.RESET, result)
        val after = h.database.playerProfileDao().get()!!
        assertFalse(after.playtimeBackfilled)
        assertEquals(0, h.database.gameDao().getById(440L)?.backfillMinutes)
        // Tracked sessions are untouched; XP falls back to tracked-only (30 + none imported = 30).
        assertEquals(sessionsBefore, h.database.sessionDao().getAll())
        assertEquals(streakBefore, after.longestStreak)
        assertEquals(30L, after.totalXp)
    }

    @Test
    fun resetIsBlockedWhileCloudImportedPlayTransferIsApplied() = runTest {
        val h = harness()
        h.seedConfirmedProfile()
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))
        h.useCase.importSteamHistory(request())
        h.settings.setCloudPresenceRefilingApplied(true)
        h.settings.setCloudPresenceRefilingReceipt(
            com.example.backlogium.data.repo.CloudPresenceRefilingReceipt(
                operationId = "op-1",
                account = steamId,
                startChoice = "CUSTOM_LOCAL_DATE",
                selectedStartAt = 1L,
                effectiveStartAt = 1L,
                throughAt = 2L,
                coveredStartAt = null,
                coveredEndAt = null,
                confirmedCutoffAt = null,
                zoneId = "UTC",
                pagesFetched = 1,
                transitionsFetched = 1,
                sessionsRefiled = 1,
                datesAffected = listOf("2026-07-26"),
                createdSessionIds = emptyList(),
                transferredMinutesByAppId = listOf(
                    com.example.backlogium.data.repo.CloudPresenceRefilingGameMinutes(440L, 40),
                ),
                remainingImportedMinutesByAppId = listOf(
                    com.example.backlogium.data.repo.CloudPresenceRefilingGameMinutes(440L, 60),
                ),
            ),
        )

        val result = h.useCase.reset()

        assertEquals(PlaytimeBackfillResetResult.BLOCKED_BY_CLOUD_TRANSFER, result)
        val profile = h.database.playerProfileDao().get()!!
        assertTrue(profile.playtimeBackfilled)
        assertEquals(100, h.database.gameDao().getById(440L)?.backfillMinutes)
    }

    @Test
    fun resetWithNoProfileIsANoOp() = runTest {
        val h = harness()
        assertEquals(PlaytimeBackfillResetResult.NO_OP, h.useCase.reset())
    }

    @Test
    fun resetBlockedDuringPendingAccountChangeDoesNotTouchState() = runTest {
        // The reset's account-admission fence: while a durable account change waits, the reset
        // neither reads nor modifies the (old account's) profile.
        val h = harness(pendingReset = steamId)
        h.database.playerProfileDao().upsert(
            PlayerProfile(steamId = steamId, playtimeBackfilled = true),
        )
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100, backfill = 100))

        val result = h.useCase.reset()

        assertEquals(PlaytimeBackfillResetResult.BLOCKED_BY_ACCOUNT_CHANGE, result)
        val profile = h.database.playerProfileDao().get()!!
        assertTrue(profile.playtimeBackfilled)
        assertFalse(profile.pendingImportRecompute)
        assertEquals(100, h.database.gameDao().getById(440L)?.backfillMinutes)
    }

    @Test
    fun resetBoundToAPreviousAccountDoesNotTouchTheReplacementAccount() = runTest {
        // A reset requested while account "old" was active must never run once the active account
        // is a replacement — the commit-boundary revalidation refuses it (BLOCKED) and the new
        // account's imported offsets/flag stay untouched.
        val h = harness(active = "new-account")
        h.database.playerProfileDao().upsert(
            PlayerProfile(steamId = "new-account", playtimeBackfilled = true),
        )
        h.database.gameDao().upsert(ownedGame(appId = 440L, forever = 100, backfill = 100))

        val result = h.useCase.reset(expectedSteamId = "old-account")

        assertEquals(PlaytimeBackfillResetResult.BLOCKED_BY_ACCOUNT_CHANGE, result)
        val profile = h.database.playerProfileDao().get()!!
        assertTrue(profile.playtimeBackfilled)
        assertEquals(100, h.database.gameDao().getById(440L)?.backfillMinutes)
    }

    @Test
    fun accountMarkerFlippedWhileWaitingForDerivedBoundarySupersedesTheImport() = runTest {
        // The durable account-change marker is recorded outside the process lock, so it can flip
        // into place while an import waits for the derived mutex. The derived-boundary recheck must
        // catch it: the raw commit is refused (Superseded) and no offset/flag is written.
        val flipped = FlipMarkerGateway(activeAccount = steamId)
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            val marks = InMemoryProgressMarksStore()
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
            val useCase = PlaytimeBackfillUseCase(
                gameDao = database.gameDao(),
                sessionDao = database.sessionDao(),
                playerProfileDao = database.playerProfileDao(),
                settings = settings,
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(database),
                syncCoordinator = SteamSyncCoordinator(),
                account = flipped,
            )
            database.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, confirmedLibrarySteamId = steamId, confirmedLibraryAt = 1L),
            )
            database.gameDao().upsert(ownedGame(appId = 440L, forever = 100))

            val import = async { useCase.importSteamHistory(request()) }
            // Block the import at its derived-boundary recheck (the second pendingReset read).
            flipped.recheckEntered.await()
            // ...and flip the durable marker into place while the import waits.
            flipped.pending = steamId
            flipped.recheckRelease.complete(Unit)

            val result = import.await()

            assertEquals(HistoryImportResult.Superseded, result)
            assertFalse("the superseded import wrote no flag", database.playerProfileDao().get()!!.playtimeBackfilled)
            assertFalse("the superseded import wrote no marker", database.playerProfileDao().get()!!.pendingImportRecompute)
            assertEquals(0, database.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            database.close()
        }
    }

    @Test
    fun resetRawCommitLandingButRecomputeFailingReportsPendingRecompute() = runTest {
        // The raw reset unit (offsets + flag, atomically) lands with a pending admin marker; the
        // derived recompute fails -> RESET_PENDING_RECOMPUTE, and the marker carries the recovery
        // forward so a stale imported aggregate is never mistaken for a completed reset.
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            val failingProfileDao = FailOnUpdateGamificationDao(database.playerProfileDao())
            val updater = GamificationUpdater(
                sessionDao = database.sessionDao(),
                dailyProgressDao = database.dailyProgressDao(),
                playerProfileDao = failingProfileDao,
                hltbDataDao = database.hltbDataDao(),
                achievementDao = database.achievementDao(),
                gameDao = database.gameDao(),
                hiddenGameDao = database.hiddenGameDao(),
                progressMarksStore = InMemoryProgressMarksStore(),
            )
            val useCase = PlaytimeBackfillUseCase(
                gameDao = database.gameDao(),
                sessionDao = database.sessionDao(),
                playerProfileDao = failingProfileDao,
                settings = settings,
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(database),
                syncCoordinator = SteamSyncCoordinator(),
                account = Gateway(steamId, null),
            )
            database.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, playtimeBackfilled = true),
            )
            database.gameDao().upsert(ownedGame(appId = 440L, forever = 100, backfill = 100))

            val result = useCase.reset()

            assertEquals(PlaytimeBackfillResetResult.RESET_PENDING_RECOMPUTE, result)
            val stored = database.playerProfileDao().get()!!
            assertFalse("the reset's raw unit committed", stored.playtimeBackfilled)
            assertEquals(0, database.gameDao().getById(440L)?.backfillMinutes)
            assertTrue("pending admin recovery carries the unfinished recompute", stored.pendingImportRecompute)
            assertEquals(RecomputeSource.BACKFILL.name, stored.pendingImportRecomputeSource)
        } finally {
            database.close()
        }
    }

    // --- 5.1: repository-facing state machine sanity ---------------------------------------

    @Test
    fun resultProjectionDistinguishesEverySharedState() {
        val committed = HistoryImportResult.Imported(creditedGames = 0, creditedMinutes = 0)
        val already = HistoryImportResult.AlreadyImported
        val needsBaseline = HistoryImportResult.NeedsBaseline
        val pending = HistoryImportResult.PendingRecompute
        val failed = HistoryImportResult.Failed("boom")
        val superseded = HistoryImportResult.Superseded

        assertTrue(committed.isFullyImported)
        assertTrue(already.isFullyImported)
        assertFalse(needsBaseline.isFullyImported)
        assertFalse(pending.isFullyImported)
        assertFalse(failed.isFullyImported)
        assertFalse(superseded.isFullyImported)
        assertNotNull(failed.reason)
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = Instant.parse("2026-07-26T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 7, 26)
    }

    private fun utc(value: String): Long = Instant.parse(value).toEpochMilli()
}
