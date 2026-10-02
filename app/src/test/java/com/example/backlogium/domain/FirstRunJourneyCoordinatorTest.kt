package com.example.backlogium.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.history.DataStoreHistoryImportRequestStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.FirstRunJourneyRepository
import com.example.backlogium.work.SteamSyncCoordinator
import com.example.backlogium.work.setup.FakeSetupStateStore
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 * First-run journey phase reconciliation against the real shared import coordinator and Room store
 * (stabilize-first-run-setup, tasks 6.1/6.4/7.2): consent-before-phase repair, exact phase-UUID
 * replay after a kill between the phase write and the consent store, DEFERRED recovery without
 * reopening, and the active-account fence.
 */
@RunWith(RobolectricTestRunner::class)
class FirstRunJourneyCoordinatorTest {

    private val steamId = "76561198000000001"
    private val otherAccount = "76561198000000002"
    private lateinit var requestStore: DataStoreHistoryImportRequestStore

    @Before
    fun clearSharedRequestStore() {
        // Robolectric reuses the process, so the shared request DataStore file persists across
        // tests; clearAll wipes the settled/voided bookkeeping too, not just the active request.
        requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())
        runBlocking { requestStore.clearAll() }
    }

    private class Gateway(
        private val active: String?,
        private val pendingReset: String? = null,
    ) : HistoryImportAccountGateway {
        override suspend fun activeSteamId(): String? = active
        override suspend fun pendingResetSteamId(): String? = pendingReset
    }

    private class Creds(var active: String?) : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured? =
            active?.let { CredentialsState.Configured(apiKey = "key", steamId = it) }
    }

    private class Facts(
    private var baseline: Boolean = true,
    private var backfilled: Boolean = false,
    private var recompute: Boolean = false,
) : LibraryBaselineGateway {
    override suspend fun baselineConfirmed(): Boolean = baseline
    override suspend fun importBackfilled(steamId: String): Boolean = backfilled
    override suspend fun recomputePending(steamId: String): Boolean = recompute
}

    private class Harness(
        val database: BacklogiumDatabase,
        val repo: FirstRunJourneyRepository,
        val coordinator: FirstRunJourneyCoordinator,
        val credentials: Creds,
        private val appScope: CoroutineScope,
        private val dataScope: CoroutineScope,
        private val root: java.io.File,
    ) {
        fun close() {
            appScope.cancel()
            dataScope.cancel()
            database.close()
            root.deleteRecursively()
        }
    }

    private suspend fun harness(
        activeAccount: String = steamId,
        legacyClaim: Boolean = false,
        facts: LibraryBaselineGateway = Facts(),
        transaction: (BacklogiumDatabase) -> DatabaseTransactionScope = { RoomDatabaseTransactionScope(it) },
    ): Harness {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
        val credentials = Creds(activeAccount)

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
            transaction = transaction(database),
            syncCoordinator = SteamSyncCoordinator(),
            account = Gateway(activeAccount),
        )
        val imports = HistoryImportCoordinator(
            requestStore = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication()),
            backfill = useCase,
            account = Gateway(activeAccount),
            time = FixedTime,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        val root = Files.createTempDirectory("first-run-journey-coord").toFile()
        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = dataScope) {
            root.resolve("journey.preferences_pb")
        }
        val repo = FirstRunJourneyRepository(data)
        val setupStore = FakeSetupStateStore()
        setupStore.setFirstRunSetupActive(legacyClaim)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = FirstRunJourneyCoordinator(
            journeyRepo = repo,
            historyImport = imports,
            credentials = credentials,
            setupStore = setupStore,
            facts = facts,
            appScope = appScope,
        )
        return Harness(database, repo, coordinator, credentials, appScope, dataScope, root)
    }

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

    private suspend fun seedAlreadyImported(database: BacklogiumDatabase, account: String = steamId) {
        database.playerProfileDao().upsert(
            PlayerProfile(steamId = account, playtimeBackfilled = true),
        )
        database.gameDao().upsert(
            Game(
                appId = 440L,
                name = "Game 440",
                iconUrl = "",
                playtimeForever = 100,
                playtime2Weeks = 0,
                lastPlaytime = 100,
                backfillMinutes = 100,
            ),
        )
    }

    // ------------------------------------------------------------------- consent-before-phase

    @Test
    fun consentBeforePhaseRepairsHistoryChoiceToTheExactRequest() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(h.coordinator.setupDone(steamId))
            assertEquals(FirstRunPhase.HISTORY_CHOICE, h.repo.current()?.phase)
            seedConfirmed(h.database)
            // The store proves an explicit consent for this account that died before the phase.
            requestStore.recordExplicitRequest(steamId, 1L, "req-before-phase")

            h.coordinator.startupRecovery()

            // The import completed and the phase only advances because the request was exact.
            assertEquals(FirstRunPhase.COMPLETE, h.repo.current()?.phase)
            assertTrue(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertNull(requestStore.request())
        } finally {
            h.close()
        }
    }

    @Test
    fun historyChoiceWithoutConsentNeverStartsAnImport() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(h.coordinator.setupDone(steamId))
            seedConfirmed(h.database)

            h.coordinator.startupRecovery()

            assertEquals(FirstRunPhase.HISTORY_CHOICE, h.repo.current()?.phase)
            assertNull(requestStore.request())
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
        } finally {
            h.close()
        }
    }

    // ----------------------------------------------------------------- kill between phase and consent store

    @Test
    fun killBetweenPhasePersistAndCoordinatorStoreReplaysTheExactPhaseUuid() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = harness(
            transaction = { db ->
                GatedTransactionScope(RoomDatabaseTransactionScope(db), entered, release)
            },
        )
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-1",
                ),
            )
            seedConfirmed(h.database)

            val recovery = async { h.coordinator.startupRecovery() }
            entered.await()

            // The consent recorded for the replay is the EXACT phase UUID, never a new one.
            val recorded = requestStore.request()
            assertNotNull(recorded)
            assertEquals("phase-1", recorded!!.requestId)
            assertEquals(steamId, recorded.steamId)
            assertEquals(FirstRunPhase.IMPORT_REQUESTED, h.repo.current()?.phase)

            release.complete(Unit)
            recovery.await()

            assertEquals(FirstRunPhase.COMPLETE, h.repo.current()?.phase)
        } finally {
            h.close()
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

    @Test
    fun replayAfterFullSettlementIsAlreadyImportedAndNeverRefreezes() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-settled",
                ),
            )
            seedAlreadyImported(h.database)

            h.coordinator.startupRecovery()

            assertEquals(FirstRunPhase.COMPLETE, h.repo.current()?.phase)
            // Exact-request completion; the durable receipt was not re-imported or refrozen.
            assertEquals(100, h.database.gameDao().getById(440L)?.backfillMinutes)
        } finally {
            h.close()
        }
    }

    // ----------------------------------------------------------------------------- DEFERRED

    @Test
    fun deferredWithRequestIdSettlesTheAdmittedConsentWithoutReopening() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            // The user chose do-later while an import was admitted (DEFERRED retains the request).
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-deferred",
                ),
            )
            assertTrue(h.coordinator.skipHistory(steamId))
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
            seedConfirmed(h.database)

            h.coordinator.startupRecovery()

            // The admitted consent was recovered (import ran), the journey stayed deferred, and the
            // fully successful settlement DROPPED the retained pointer — a later Settings reset can
            // never be silently re-imported from it at startup.
            assertTrue(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
            assertNull(h.repo.current()?.importRequestId)
            assertFalse(h.repo.current()!!.owed)
        } finally {
            h.close()
        }
    }

    @Test
    fun deferredWithoutARequestIdNeverStartsAnImport() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(h.coordinator.setupDone(steamId))
            assertTrue(h.coordinator.skipHistory(steamId))
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
            seedConfirmed(h.database)

            h.coordinator.startupRecovery()

            assertNull(requestStore.request())
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertFalse(h.repo.current()!!.owed)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------- durable reset invalidation (voiding)

    @Test
    fun settledDeferredThenResetCannotReplayTheOldConsent() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-settled",
                ),
            )
            assertTrue(h.coordinator.skipHistory(steamId))
            seedConfirmed(h.database)

            // The admitted import fully settles; the successful settlement drops the pointer.
            h.coordinator.startupRecovery()
            assertTrue(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
            assertNull(h.repo.current()?.importRequestId)

            // A Settings reset wipes the one-time flag, offsets, and the request store.
            h.database.playerProfileDao().upsert(
                PlayerProfile(steamId = steamId, playtimeBackfilled = false),
            )
            h.database.gameDao().upsert(
                Game(
                    appId = 440L, name = "Game 440", iconUrl = "",
                    playtimeForever = 100, playtime2Weeks = 0, lastPlaytime = 100,
                    backfillMinutes = 0,
                ),
            )
            requestStore.clear()

            // A later cold start has no pointer to replay: nothing re-imports the old consent.
            h.coordinator.startupRecovery()
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
            assertNull(h.repo.current()?.importRequestId)
            assertFalse(h.repo.current()!!.owed)
        } finally {
            h.close()
        }
    }

    @Test
    fun voidedDeferredPointerIsClearedAndNeverReplayed() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-voided",
                ),
            )
            assertTrue(h.coordinator.skipHistory(steamId))
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
            seedConfirmed(h.database)
            // A Settings reset durably invalidated the exact admitted request.
            requestStore.recordVoidedImport(steamId, "phase-voided")

            h.coordinator.startupRecovery()

            // The voided consent is never replayed (nothing re-imports), the pointer is dropped,
            // and DEFERRED is preserved — never owed, never reopened.
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
            assertNull(h.repo.current()?.importRequestId)
            assertFalse(h.repo.current()!!.owed)
        } finally {
            h.close()
        }
    }

    @Test
    fun voidedImportRequestedPhaseReturnsToHistoryChoiceWithoutConsent() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-voided",
                ),
            )
            seedConfirmed(h.database)
            requestStore.recordVoidedImport(steamId, "phase-voided")

            h.coordinator.startupRecovery()

            // Never replayed, never re-consented; the owed decision is restored without a new UUID.
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(FirstRunPhase.HISTORY_CHOICE, h.repo.current()?.phase)
            assertNull(h.repo.current()?.importRequestId)
            assertTrue(h.repo.current()!!.owed)
            assertNull(requestStore.request())
        } finally {
            h.close()
        }
    }

    // ----------------------------------------------------------------------------- account fence

    @Test
    fun replacementAccountIsNeverAdvancedByAnOldPhaseOrRequest() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-a",
                ),
            )
            requestStore.recordExplicitRequest(steamId, 1L, "phase-a")
            seedConfirmed(h.database, account = otherAccount)
            h.credentials.active = otherAccount

            h.coordinator.startupRecovery()

            // The old account's phase is untouched and the replacement account imports nothing.
            assertEquals(steamId, h.repo.current()?.accountSteamId)
            assertEquals(FirstRunPhase.IMPORT_REQUESTED, h.repo.current()?.phase)
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
        } finally {
            h.close()
        }
    }

    // ----------------------------------------------------------- legacy claim migration + owed

    @Test
    fun legacyClaimMigratesToSetupAndTheOwedProjectionResolves() = runBlocking {
        val h = harness(legacyClaim = true)
        try {
            // Unknown until the first resolution completes.
            assertNull(h.coordinator.firstRunOwed.value)
            h.coordinator.startupRecovery()

            assertEquals(FirstRunPhase.SETUP, h.repo.current()?.phase)
            assertEquals(true, h.coordinator.firstRunOwed.value)
        } finally {
            h.close()
        }
    }

    @Test
    fun configuredInstallWithoutAClaimIsNotOwed() = runBlocking {
        val h = harness()
        try {
            // No claim; the one-shot migration leaves a configured install out of onboarding.
            h.coordinator.startupRecovery()
            assertNull(h.repo.current())
            assertEquals(false, h.coordinator.firstRunOwed.value)
        } finally {
            h.close()
        }
    }

    // ----------------------------------------------------------------- account re-fence + guards

    @Test
    fun finishHistoryCompletesAnOrdinaryAlreadyImportedJourney() = runBlocking {
        val h = harness(facts = Facts(backfilled = true))
        try {
            h.repo.claim(steamId)
            assertTrue(h.coordinator.setupDone(steamId))
            assertEquals(FirstRunPhase.HISTORY_CHOICE, h.repo.current()?.phase)

            // Durable receipt present, no pending recompute: the Continue completes the decision.
            assertTrue(h.coordinator.finishHistory(steamId))
            assertEquals(FirstRunPhase.COMPLETE, h.repo.current()?.phase)
            assertFalse(h.repo.current()!!.owed)
        } finally {
            h.close()
        }
    }

    @Test
    fun finishHistoryOnAlreadyCompleteOrDeferredReturnsTrue() = runBlocking {
        val h = harness(facts = Facts(backfilled = true))
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-done",
                ),
            )
            assertTrue(h.repo.completeImport(steamId, "phase-done"))
            // The coordinator already completed it synchronously: the VM may exit.
            assertTrue(h.coordinator.finishHistory(steamId))
            assertEquals(FirstRunPhase.COMPLETE, h.repo.current()?.phase)

            // An explicitly deferred journey (old pending settled) also reports done-but-not-owed.
            h.repo.claim(steamId)
            assertTrue(h.coordinator.setupDone(steamId))
            assertTrue(h.coordinator.skipHistory(steamId))
            assertTrue(h.coordinator.finishHistory(steamId))
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
        } finally {
            h.close()
        }
    }

    @Test
    fun finishHistoryWhileRawImportPendsRecomputeReturnsFalse() = runBlocking {
        val h = harness(facts = Facts(backfilled = true, recompute = true))
        try {
            h.repo.claim(steamId)
            assertTrue(
                h.repo.transition(
                    steamId,
                    setOf(FirstRunPhase.SETUP),
                    FirstRunPhase.IMPORT_REQUESTED,
                    "phase-r",
                ),
            )

            // Raw flag is set but the recompute marker is still pending: not a done decision.
            assertFalse(h.coordinator.finishHistory(steamId))
            assertEquals(FirstRunPhase.IMPORT_REQUESTED, h.repo.current()?.phase)
            assertTrue(h.repo.current()!!.owed)
        } finally {
            h.close()
        }
    }

    @Test
    fun staleAccountFinishSkipAndSetupNeverAdvanceOrDismiss() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(h.coordinator.setupDone(steamId)) // HISTORY_CHOICE for steamId
            h.credentials.active = otherAccount

            // The active account is now the replacement: none of these may act on steamId's journey.
            assertFalse(h.coordinator.finishHistory(steamId))
            assertFalse(h.coordinator.skipHistory(steamId))
            assertFalse(h.coordinator.setupDone(steamId))
            assertEquals(FirstRunPhase.HISTORY_CHOICE, h.repo.current()?.phase)
            assertTrue(h.repo.current()!!.owed)
        } finally {
            h.close()
        }
    }

    @Test
    fun accountChangeReFencesTheOwedProjectionAfterCredentialsSwitch() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            h.coordinator.startupRecovery()
            assertEquals(true, h.coordinator.firstRunOwed.value)

            // Credentials switch to a replacement account with no journey: Home must stop owing.
            h.credentials.active = otherAccount
            h.coordinator.refreshAccountOwed()
            assertEquals(false, h.coordinator.firstRunOwed.value)
        } finally {
            h.close()
        }
    }

    @Test
    fun requestImportAfterDeferredReturnsSupersededWithoutLaunching() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            assertTrue(h.coordinator.setupDone(steamId))
            assertTrue(h.coordinator.skipHistory(steamId))
            seedConfirmed(h.database)

            val result = h.coordinator.requestImport(steamId)

            assertEquals(HistoryImportResult.Superseded, result)
            assertNull(requestStore.request())
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(FirstRunPhase.DEFERRED, h.repo.current()?.phase)
        } finally {
            h.close()
        }
    }

    @Test
    fun requestImportForAWrongAccountReturnsSupersededWithoutConsent() = runBlocking {
        val h = harness()
        try {
            h.repo.claim(steamId)
            seedConfirmed(h.database)
            // The journey belongs to steamId; the active account is already the replacement.
            h.credentials.active = otherAccount

            val result = h.coordinator.requestImport(otherAccount)

            assertEquals(HistoryImportResult.Superseded, result)
            assertNull(requestStore.request())
            assertFalse(h.database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(FirstRunPhase.SETUP, h.repo.current()?.phase)
        } finally {
            h.close()
        }
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = Instant.parse("2026-07-26T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 7, 26)
    }
}