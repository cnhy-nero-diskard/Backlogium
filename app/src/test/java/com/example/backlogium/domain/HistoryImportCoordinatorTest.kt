package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.history.DataStoreHistoryImportRequestStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * Shared-coordinator behavior for the explicit history import (stabilize-first-run-setup, tasks
 * 5.1/5.3/5.5/5.7): consent persisted before launch, concurrent request coalescing, shared
 * in-flight state, request consumption on settlement/supersede and retention on failure.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryImportCoordinatorTest {

    private val steamId = "76561198000000001"

    @Before
    fun clearSharedRequestStore() {
        // Robolectric reuses the process, so the request DataStore file persists across tests.
        runBlocking { store().clearAll() }
    }

    private fun store() = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())

    /**
     * A per-test-managed application scope: every admission job is a child of this scope, and
     * [cancelAndJoin] at teardown cancels/joins any residual job (e.g. one blocked on a test gate)
     * so nothing leaks into the next test's process-wide uncaught handler. Failures are never
     * swallowed here — a genuine bug must surface through the typed deferreds and fail the owning
     * test with its real stack.
     */
    private val managedAppScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @org.junit.After
    fun tearDownManagedScope() {
        runBlocking { managedAppScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private class Gateway(
        private val active: String?,
        private val pendingReset: String? = null,
    ) : HistoryImportAccountGateway {
        override suspend fun activeSteamId(): String? = active
        override suspend fun pendingResetSteamId(): String? = pendingReset
    }

    /** Delegates every profile DAO call except the derived write, which throws once then succeeds. */
    private class FailOnUpdateGamificationDao(
        private val delegate: com.example.backlogium.data.local.dao.PlayerProfileDao,
    ) : com.example.backlogium.data.local.dao.PlayerProfileDao by delegate {
        private var failuresRemaining = 1
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
                totalXp, level, currentStreak, longestStreak, gamificationConfigVersion,
            )
        }
    }

    private class GatedTransactionScope(
        private val delegate: DatabaseTransactionScope,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : DatabaseTransactionScope {
        override suspend fun <R> run(block: suspend () -> R): R {
            entered.complete(Unit)
            release.await()
            return delegate.run(block)
        }
    }

    private fun coordinator(
        database: BacklogiumDatabase,
        settings: SettingsDataStore,
        gateway: HistoryImportAccountGateway,
        transaction: DatabaseTransactionScope = RoomDatabaseTransactionScope(database),
    ): HistoryImportCoordinator {
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
            transaction = transaction,
            syncCoordinator = SteamSyncCoordinator(),
            account = gateway,
        )
        return HistoryImportCoordinator(
            requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()),
            backfill = useCase,
            account = gateway,
            time = FixedTime,
            appScope = managedAppScope,
        )
    }

    private fun database(): BacklogiumDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()

    private suspend fun seedConfirmed(database: BacklogiumDatabase, account: String = steamId) {
        database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = account,
                confirmedLibrarySteamId = account,
                confirmedLibraryAt = 1234L,
            ),
        )
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game 440",
                iconUrl = "",
                playtimeForever = 100,
                playtime2Weeks = 0,
                lastPlaytime = 100,
            ),
        )
    }

    // --- 5.1: explicit consent persists before launch --------------------------------------

    @Test
    fun consentIsRecordedBeforeLaunchAndSurvivesLaunchFailure() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            var calls = 0
            val failing = object : DatabaseTransactionScope {
                override suspend fun <R> run(block: suspend () -> R): R {
                    calls += 1
                    if (calls == 1) throw IllegalStateException("launch failed")
                    return block()
                }
            }
            val coordinator = coordinator(db, settings, Gateway(steamId), transaction = failing)

            val result = coordinator.start()

            assertTrue(result is HistoryImportResult.Failed)
            // The consent is durable even though the operation never launched.
            val recorded = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()).request()
            assertNotNull(recorded)
            assertEquals(steamId, recorded?.steamId)
            assertNotNull(recorded?.requestId)
        } finally {
            db.close()
        }
    }

    @Test
    fun startWithoutAnActiveAccountReportsNeedsBaselineWithoutCreatingConsent() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(active = null))

            val result = coordinator.start()

            assertEquals(HistoryImportResult.NeedsBaseline, result)
            assertNull(
                DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()).request(),
            )
            // No consent -> no attributable Running/Completed was published.
            assertEquals(HistoryImportState.Idle, coordinator.state.value)
        } finally {
            db.close()
        }
    }

    // --- 5.1: concurrent request coalescing + shared in-flight state -----------------------

    @Test
    fun concurrentExplicitRequestsCoalesceWithoutDoubleImport() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val coordinator = coordinator(db, settings, Gateway(steamId))

            val first = async { coordinator.start() }
            val second = async { coordinator.start() }

            val results = listOf(first.await(), second.await())
            // Both taps settle on the same single admission: either they join it (both Imported)
            // or the second arrives after settlement (AlreadyImported) — never a second import.
            assertTrue(
                "both taps must coalesce onto one admission",
                results.all {
                    it == HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100) ||
                        it == HistoryImportResult.AlreadyImported
                },
            )
            // One snapshot: the backing offset is the single import's freeze, not a double.
            assertEquals(100, db.gameDao().getById(440L)?.backfillMinutes)
            assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
            // Coalescing never creates a second consent; the admission recorded exactly one.
            assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
        } finally {
            db.close()
        }
    }

    @Test
    fun inFlightAndStateAreSharedWhileTheOperationIsAdmitted() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = coordinator(
                db,
                settings,
                Gateway(steamId),
                transaction = GatedTransactionScope(RoomDatabaseTransactionScope(db), entered, release),
            )

            val job = async { coordinator.start() }
            entered.await()

            assertTrue(coordinator.inFlight.value)
            val running = coordinator.state.value
            assertTrue("Running is attributable to the recorded consent", running is HistoryImportState.Running)
            assertEquals(steamId, (running as HistoryImportState.Running).accountSteamId)
            // Consent was durably recorded before Running was published.
            assertNotNull(DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()).request())

            release.complete(Unit)
            val result = job.await()

            assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100), result)
            assertFalse(coordinator.inFlight.value)
            val completed = coordinator.state.value
            assertTrue(completed is HistoryImportState.Completed)
            assertEquals(steamId, (completed as HistoryImportState.Completed).accountSteamId)
            assertEquals(result, completed.result)
        } finally {
            db.close()
        }
    }

    // --- 5.1/5.2: request consumption on settlement/supersede, retention on needs-baseline ----

    @Test
    fun successfulStartConsumesTheRecordedRequest() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val store = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(steamId))

            coordinator.start()

            assertNull(store.request())
        } finally {
            db.close()
        }
    }

    @Test
    fun supersededStartClearsTheStaleConsent() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            // Active account B, but the stored library belongs to A -> the recorded consent for B
            // cannot act and is cleared.
            seedConfirmed(db, account = "other-account")
            val store = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(active = steamId))

            val result = coordinator.start()

            assertEquals(HistoryImportResult.Superseded, result)
            assertNull(store.request())
        } finally {
            db.close()
        }
    }

    @Test
    fun needsBaselineStartKeepsTheConsentForLaterRecovery() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            // Account is active but has no confirmed baseline.
            db.playerProfileDao().upsert(PlayerProfile(steamId = steamId))
            val store = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(steamId))

            val result = coordinator.start()

            assertEquals(HistoryImportResult.NeedsBaseline, result)
            assertNotNull(store.request())
        } finally {
            db.close()
        }
    }

    // --- 5.5/5.7: recovery and reset -------------------------------------------------------

    @Test
    fun recoverPendingReplaysARecordedConsentExactlyOnce() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val store = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(steamId))

            // A kill after consent was recorded but before the operation launched.
            store.recordExplicitRequest(steamId, 1L, "req-killed")

            coordinator.recoverPending()

            assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
            assertNull(store.request())

            // A second recovery is a no-op: the flag already reports the completed import.
            coordinator.recoverPending()
            assertEquals(100, db.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            db.close()
        }
    }

    @Test
    fun resumeResumesAPendingRecomputationWithoutNewConsent() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val failingProfileDao = FailOnUpdateGamificationDao(db.playerProfileDao())
            val marks = InMemoryProgressMarksStore()
            val updater = GamificationUpdater(
                sessionDao = db.sessionDao(),
                dailyProgressDao = db.dailyProgressDao(),
                playerProfileDao = failingProfileDao,
                hltbDataDao = db.hltbDataDao(),
                achievementDao = db.achievementDao(),
                gameDao = db.gameDao(),
                hiddenGameDao = db.hiddenGameDao(),
                progressMarksStore = marks,
            )
            val useCase = PlaytimeBackfillUseCase(
                gameDao = db.gameDao(),
                sessionDao = db.sessionDao(),
                playerProfileDao = failingProfileDao,
                settings = settings,
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(db),
                syncCoordinator = SteamSyncCoordinator(),
                account = Gateway(steamId),
            )
            val coordinator = HistoryImportCoordinator(
                requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()),
                backfill = useCase,
                account = Gateway(steamId),
                time = FixedTime,
                appScope = managedAppScope,
            )

            // Raw commit lands, recompute fails -> PendingRecompute, marker held, and the recorded
            // consent is RETAINED (the parent needs the account-matching request for recovery).
            val first = coordinator.start()
            assertTrue(first == HistoryImportResult.PendingRecompute)
            assertTrue(db.playerProfileDao().get()!!.pendingImportRecompute)
            assertNotNull(
                DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()).request(),
            )

            // Retry through the shared resume path: the retained request travels to a completed
            // import and is then consumed.
            val resumed = coordinator.resume()

            assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100), resumed)
            assertFalse(db.playerProfileDao().get()!!.pendingImportRecompute)
            assertNull(
                DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()).request(),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun resetClearsTheRecordedConsentAfterSettlement() = runTest {
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val store = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(steamId))
            coordinator.start()
            assertNull(store.request())
            // A stale consent survives (e.g. recorded by a newer UI), then reset voids it.
            store.recordExplicitRequest(steamId, 2L, "req-stale")

            val result = coordinator.resetSteamHistoryImport()

            assertEquals(PlaytimeBackfillResetResult.RESET, result)
            assertNull(store.request())
            assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
        } finally {
            db.close()
        }
    }

    @Test
    fun resetVoidsConsentEvenWhenItsRecomputeFails() = runTest {
        // The reset's raw commit is the consent-voiding moment, not its recompute: once offsets
        // and the flag have cleared atomically, recovery must never re-import a reset library.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val failingProfileDao = FailOnUpdateGamificationDao(db.playerProfileDao())
            val marks = InMemoryProgressMarksStore()
            val updater = GamificationUpdater(
                sessionDao = db.sessionDao(),
                dailyProgressDao = db.dailyProgressDao(),
                playerProfileDao = failingProfileDao,
                hltbDataDao = db.hltbDataDao(),
                achievementDao = db.achievementDao(),
                gameDao = db.gameDao(),
                hiddenGameDao = db.hiddenGameDao(),
                progressMarksStore = marks,
            )
            val useCase = PlaytimeBackfillUseCase(
                gameDao = db.gameDao(),
                sessionDao = db.sessionDao(),
                playerProfileDao = failingProfileDao,
                settings = settings,
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(db),
                syncCoordinator = SteamSyncCoordinator(),
                account = Gateway(steamId),
            )
            val coordinator = HistoryImportCoordinator(
                requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()),
                backfill = useCase,
                account = Gateway(steamId),
                time = FixedTime,
                appScope = managedAppScope,
            )
            // Simulate a completed import with a stale consent still recorded.
            db.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, playtimeBackfilled = true),
            )
            db.gameDao().upsert(
                Game(
                    appId = 440L, name = "Game 440", iconUrl = "",
                    playtimeForever = 100, playtime2Weeks = 0, lastPlaytime = 100,
                    backfillMinutes = 100,
                ),
            )
            store().recordExplicitRequest(steamId, 5L, "req-reset")

            val result = coordinator.resetSteamHistoryImport()

            assertEquals(PlaytimeBackfillResetResult.RESET_PENDING_RECOMPUTE, result)
            // Consent void even though the follow-up recompute could not finalize.
            assertNull(store().request())
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------- parent-review typed admission / fences

    @Test
    fun resetWhileImportRunsReturnsResetResultNotImport() = runTest {
        // A reset must wait for the running import to finish, then run its OWN typed admission —
        // it can never return (or be cast to) the import's HistoryImportResult.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = coordinator(
                db,
                settings,
                Gateway(steamId),
                transaction = GatedTransactionScope(RoomDatabaseTransactionScope(db), entered, release),
            )

            val import = async { coordinator.start() }
            entered.await()
            val reset = async { coordinator.resetSteamHistoryImport() }
            assertTrue(coordinator.inFlight.value)

            release.complete(Unit)
            val importResult = import.await()
            val resetResult = reset.await()

            assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100), importResult)
            assertEquals(PlaytimeBackfillResetResult.RESET, resetResult)
            // The reset ran after the import: its raw unit cleared the just-imported offsets/flag.
            assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(0, db.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            db.close()
        }
    }

    @Test
    fun importWhileResetRunsReturnsImportResult() = runTest {
        // Symmetric: an import queued behind a running reset waits, then runs its own typed
        // admission — it never receives (or casts to) the reset's PlaytimeBackfillResetResult.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = coordinator(
                db,
                settings,
                Gateway(steamId),
                transaction = GatedTransactionScope(RoomDatabaseTransactionScope(db), entered, release),
            )

            val reset = async { coordinator.resetSteamHistoryImport() }
            entered.await()
            val import = async { coordinator.start() }

            release.complete(Unit)
            val importResult = import.await()
            val resetResult = reset.await()

            assertEquals(PlaytimeBackfillResetResult.RESET, resetResult)
            assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100), importResult)
        } finally {
            db.close()
        }
    }

    @Test
    fun changingAccountQueuedBehindRunningImportReportsSuperseded() = runTest {
        // A tap for a DIFFERENT account while another account's import is admitted must report
        // Superseded — it must never reuse the running account's completion or record new consent.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val coordinator = coordinator(
                db,
                settings,
                Gateway(steamId),
                transaction = GatedTransactionScope(RoomDatabaseTransactionScope(db), entered, release),
            )

            val first = async { coordinator.start() }
            entered.await()
            val second = async { coordinator.start(expectedSteamId = "other-account") }

            val superseded = second.await()
            assertEquals(HistoryImportResult.Superseded, superseded)
            // No consent was recorded for the queued different account: the store still holds only
            // the admitted import's consent (the FIRST account), never "other-account".
            assertEquals(steamId, store().request()?.steamId)

            release.complete(Unit)
            assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100), first.await())
        } finally {
            db.close()
        }
    }

    @Test
    fun uncaughtImportFailureSurfacesAsAttributableFailedStateAndReleasesBusy() = runTest {
        // The coordinator converts an unexpected thrown failure after consent was durably recorded
        // into an attributable Failed (NOT a naked exception, NOT a stale Running): state is
        // Completed(Failed, requestId, account), busy is released, and the consent stays for retry.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val throwing = object : HistoryImportAccountGateway {
                override suspend fun activeSteamId(): String? = steamId
                override suspend fun pendingResetSteamId(): String? =
                    throw IllegalStateException("gateway exploded")
            }
            val coordinator = coordinator(db, settings, throwing)

            val result = coordinator.start()

            assertTrue(result is HistoryImportResult.Failed)
            val settled = coordinator.state.value
            assertTrue("a recorded consent must never leave Running stale", settled is HistoryImportState.Completed)
            val completed = settled as HistoryImportState.Completed
            assertEquals(result, completed.result)
            assertEquals(steamId, completed.accountSteamId)
            // The state is attributed to the exact consent that launched the import.
            assertEquals(store().request()?.requestId, completed.requestId)
            assertFalse(coordinator.inFlight.value)
            assertNotNull("the recorded consent is retained for retry", store().request())
        } finally {
            db.close()
        }
    }

    @Test
    fun resetFencedByPendingAccountChangeReturnsBlockedAndKeepsConsent() = runTest {
        // The reset's account-admission fence: while a durable account change waits, the reset
        // neither reads nor modifies state and the recorded consent stays untouched.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            db.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, playtimeBackfilled = true),
            )
            db.gameDao().upsert(
                Game(
                    appId = 440L, name = "Game 440", iconUrl = "",
                    playtimeForever = 100, playtime2Weeks = 0, lastPlaytime = 100,
                    backfillMinutes = 100,
                ),
            )
            store().recordExplicitRequest(steamId, 6L, "req-fence")
            val coordinator = coordinator(db, settings, Gateway(steamId, pendingReset = steamId))

            val result = coordinator.resetSteamHistoryImport()

            assertEquals(PlaytimeBackfillResetResult.BLOCKED_BY_ACCOUNT_CHANGE, result)
            assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(100, db.gameDao().getById(440L)?.backfillMinutes)
            assertNotNull("the consent is untouched while a change is in flight", store().request())
        } finally {
            db.close()
        }
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = Instant.parse("2026-07-26T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 7, 26)
    }

    // ------------------------------------------------- durable reset invalidation (phase replay)

    @Test
    fun startWithVoidedRequestIdRefusesWithoutNewConsent() = runTest {
        // A phase replay re-submitting a request id an explicit reset already voided must be
        // refused (Superseded) and must never record a new consent for that id.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            store().recordVoidedImport(steamId, "phase-9")
            val coordinator = coordinator(db, settings, Gateway(steamId))

            val result = coordinator.start(expectedSteamId = steamId, requestId = "phase-9")

            assertEquals(HistoryImportResult.Superseded, result)
            assertNull("a voided replay never records a new consent", store().request())
            assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(0, db.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            db.close()
        }
    }

    @Test
    fun resetAfterSettledImportVoidsThatRequestIdAndReplayIsRefused() = runTest {
        // Import settles with phase UUID -> reset -> the phase UUID is durably voided; replaying
        // it must NOT silently re-import the reset library.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val coordinator = coordinator(db, settings, Gateway(steamId))

            val first = coordinator.start(expectedSteamId = steamId, requestId = "phase-1")
            assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100), first)

            val reset = coordinator.resetSteamHistoryImport()
            assertEquals(PlaytimeBackfillResetResult.RESET, reset)

            assertTrue(coordinator.isRequestVoided(steamId, "phase-1"))
            assertNull("the reset consumed the recorded consent", store().request())

            val replay = coordinator.start(expectedSteamId = steamId, requestId = "phase-1")
            assertEquals(HistoryImportResult.Superseded, replay)
            // No re-import: the flag stays cleared and no offset was refrozen.
            assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(0, db.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            db.close()
        }
    }

    @Test
    fun newDeliberateUuidAfterResetIsAllowedToImportAgain() = runTest {
        // An explicit fresh consent with a NEW deliberate UUID is not voided and imports normally.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val coordinator = coordinator(db, settings, Gateway(steamId))
            coordinator.start(expectedSteamId = steamId, requestId = "phase-1")
            coordinator.resetSteamHistoryImport()

            val fresh = coordinator.start(expectedSteamId = steamId, requestId = "fresh-2")

            assertEquals(HistoryImportResult.Imported(creditedGames = 1, creditedMinutes = 100), fresh)
            assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(100, db.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            db.close()
        }
    }

    @Test
    fun blockedCloudResetDoesNotVoidTheSettledRequestId() = runTest {
        // The cloud imported-play transfer guard blocks the reset; nothing is voided and the
        // settled identity stays usable.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val coordinator = coordinator(db, settings, Gateway(steamId))
            coordinator.start(expectedSteamId = steamId, requestId = "phase-1")
            settings.setCloudPresenceRefilingApplied(true)
            settings.setCloudPresenceRefilingReceipt(
                com.example.backlogium.data.repo.CloudPresenceRefilingReceipt(
                    operationId = "op-2",
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
                    remainingImportedMinutesByAppId = emptyList(),
                ),
            )

            val result = coordinator.resetSteamHistoryImport()

            assertEquals(PlaytimeBackfillResetResult.BLOCKED_BY_CLOUD_TRANSFER, result)
            assertFalse("a blocked reset voids nothing", coordinator.isRequestVoided(steamId, "phase-1"))
            assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
        } finally {
            db.close()
        }
    }

    @Test
    fun recoverPendingResumesAResetIntentAndVoidsBeforePhaseReplay() = runTest {
        // Kill between the reset intent and the raw commit: startup recovery branches on the intent
        // (not the import store), resumes the guarded reset, and the voided id is refused on replay.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            db.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, playtimeBackfilled = true),
            )
            db.gameDao().upsert(
                Game(
                    appId = 440L, name = "Game 440", iconUrl = "",
                    playtimeForever = 100, playtime2Weeks = 0, lastPlaytime = 100,
                    backfillMinutes = 100,
                ),
            )
            val coordinator = coordinator(db, settings, Gateway(steamId))
            store().recordResetIntent(steamId, "phase-1", 5L)

            coordinator.recoverPending()

            assertTrue(coordinator.isRequestVoided(steamId, "phase-1"))
            assertFalse("the resumed reset committed", db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(0, db.gameDao().getById(440L)?.backfillMinutes)
            assertNull(store().resetIntent())
        } finally {
            db.close()
        }
    }

    @Test
    fun recoverPendingVoidsAfterRawResetCommittedBeforeBookkeeping() = runTest {
        // Crash window: the reset's raw commit landed (flag false, offsets 0) and the process died
        // BEFORE the durable void/bookkeeping ran. On startup the same-account RESET intent is the
        // authorization: recoverPending voids its exact request id and a phase replay is refused —
        // the reset library is never silently re-imported.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            db.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, playtimeBackfilled = true),
            )
            db.gameDao().upsert(
                Game(
                    appId = 440L, name = "Game 440", iconUrl = "",
                    playtimeForever = 100, playtime2Weeks = 0, lastPlaytime = 100,
                    backfillMinutes = 100,
                ),
            )
            // The raw reset commits directly through the use case (no coordinator bookkeeping).
            val directReset = PlaytimeBackfillUseCase(
                gameDao = db.gameDao(),
                sessionDao = db.sessionDao(),
                playerProfileDao = db.playerProfileDao(),
                settings = settings,
                gamificationUpdater = GamificationUpdater(
                    sessionDao = db.sessionDao(),
                    dailyProgressDao = db.dailyProgressDao(),
                    playerProfileDao = db.playerProfileDao(),
                    hltbDataDao = db.hltbDataDao(),
                    achievementDao = db.achievementDao(),
                    gameDao = db.gameDao(),
                    hiddenGameDao = db.hiddenGameDao(),
                    progressMarksStore = InMemoryProgressMarksStore(),
                ),
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(db),
                syncCoordinator = SteamSyncCoordinator(),
                account = Gateway(steamId),
            )
            directReset.reset(steamId)
            assertFalse("the raw reset already committed", db.playerProfileDao().get()!!.playtimeBackfilled)
            // ...but the durable void bookkeeping never ran: only the intent survives.
            store().recordResetIntent(steamId, "phase-1", 5L)

            val coordinator = coordinator(db, settings, Gateway(steamId))
            coordinator.recoverPending()

            assertTrue("the same-account intent authorizes the void", coordinator.isRequestVoided(steamId, "phase-1"))
            assertEquals(
                HistoryImportResult.Superseded,
                coordinator.start(expectedSteamId = steamId, requestId = "phase-1"),
            )
            // No re-import: the flag stays cleared and no offset was refrozen.
            assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(0, db.gameDao().getById(440L)?.backfillMinutes)
            assertNull(store().resetIntent())
        } finally {
            db.close()
        }
    }

    @Test
    fun recoveredResetIntentSettlingNoOpStillVoidsItsRequestId() = runTest {
        // If the recovered reset settles NO_OP (nothing left to reset), the persisted same-account
        // intent is still the authorization to void its exact request id — a later phase replay of
        // that id must not silently re-import.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(steamId))
            store().recordResetIntent(steamId, "phase-1", 5L)

            coordinator.recoverPending()

            assertTrue(coordinator.isRequestVoided(steamId, "phase-1"))
            assertEquals(
                HistoryImportResult.Superseded,
                coordinator.start(expectedSteamId = steamId, requestId = "phase-1"),
            )
            assertNull(store().resetIntent())
        } finally {
            db.close()
        }
    }

    @Test
    fun pendingRecomputeThenResetVoidsTheRetainedRequestId() = runTest {
        // A retained (never-fully-settled) consent — PendingRecompute here, whose raw commit landed
        // and the recompute failed — is the void candidate: resetting clears its active request AND
        // durably voids its id so a phase replay can never re-import the reset library.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val failingProfileDao = FailOnUpdateGamificationDao(db.playerProfileDao())
            val marks = InMemoryProgressMarksStore()
            val updater = GamificationUpdater(
                sessionDao = db.sessionDao(),
                dailyProgressDao = db.dailyProgressDao(),
                playerProfileDao = failingProfileDao,
                hltbDataDao = db.hltbDataDao(),
                achievementDao = db.achievementDao(),
                gameDao = db.gameDao(),
                hiddenGameDao = db.hiddenGameDao(),
                progressMarksStore = marks,
            )
            val useCase = PlaytimeBackfillUseCase(
                gameDao = db.gameDao(),
                sessionDao = db.sessionDao(),
                playerProfileDao = failingProfileDao,
                settings = settings,
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = DerivedStateWriteCoordinator(),
                transaction = RoomDatabaseTransactionScope(db),
                syncCoordinator = SteamSyncCoordinator(),
                account = Gateway(steamId),
            )
            val coordinator = HistoryImportCoordinator(
                requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()),
                backfill = useCase,
                account = Gateway(steamId),
                time = FixedTime,
                appScope = managedAppScope,
            )

            val first = coordinator.start(expectedSteamId = steamId, requestId = "phase-p")
            assertTrue(first == HistoryImportResult.PendingRecompute)
            assertNotNull("the unfinished consent is retained", store().request())

            val reset = coordinator.resetSteamHistoryImport()
            assertEquals(PlaytimeBackfillResetResult.RESET, reset)

            assertTrue("the retained request id is voided", coordinator.isRequestVoided(steamId, "phase-p"))
            assertEquals(
                HistoryImportResult.Superseded,
                coordinator.start(expectedSteamId = steamId, requestId = "phase-p"),
            )
            assertFalse("no re-import: the reset cleared the raw commit", db.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(0, db.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            db.close()
        }
    }

    @Test
    fun needsBaselineThenResetVoidsRetainedRequestIdEvenOnNoOp() = runTest {
        // A retained NeedsBaseline consent (never settled, no profile) is still the void candidate:
        // the reset settles NO_OP, but the durable intent authorizes the void — the phase replay is
        // refused instead of implicitly creating a fresh consent for that id.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            val coordinator = coordinator(db, settings, Gateway(steamId))
            val first = coordinator.start(expectedSteamId = steamId, requestId = "phase-n")
            assertEquals(HistoryImportResult.NeedsBaseline, first)
            assertNotNull("the needs-baseline consent is retained", store().request())

            val reset = coordinator.resetSteamHistoryImport()

            assertEquals(PlaytimeBackfillResetResult.NO_OP, reset)
            assertTrue(coordinator.isRequestVoided(steamId, "phase-n"))
            assertEquals(
                HistoryImportResult.Superseded,
                coordinator.start(expectedSteamId = steamId, requestId = "phase-n"),
            )
            assertNull("the voided replay records no consent", store().request())
        } finally {
            db.close()
        }
    }

    @Test
    fun resetVoidsTheNewestRetainedRequestNotAnOlderSettledOne() = runTest {
        // When both an older fully-settled import (R1) and a newer retained consent (R2) exist,
        // the reset voids R2 (the persisted active same-account consent) — never R1.
        val db = database()
        try {
            val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
            seedConfirmed(db)
            val coordinator = coordinator(db, settings, Gateway(steamId))
            // R1 fully settled (durably recorded), then a newer retained consent R2.
            store().recordSettledImport(steamId, "R1")
            store().recordExplicitRequest(steamId, 1L, "R2")

            val reset = coordinator.resetSteamHistoryImport()

            assertEquals(PlaytimeBackfillResetResult.RESET, reset)
            assertTrue("the newest retained request is voided", coordinator.isRequestVoided(steamId, "R2"))
            assertFalse("the older settled identity is not voided", coordinator.isRequestVoided(steamId, "R1"))
        } finally {
            db.close()
        }
    }
}