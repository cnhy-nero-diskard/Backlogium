package com.example.backlogium.data.backup

import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.credentials.EncryptedCredentialStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.PlaySessionEndPublisher
import com.example.backlogium.data.repo.PresenceObserver
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.SteamSyncCoordinator
import com.example.backlogium.work.SyncScheduler
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The real import entry point must reject an unusable observation epoch before it enters any
 * account, visibility, transaction, or derived-state boundary. The receiving database is seeded
 * so this checks preservation as well as the absence of imported rows.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryRecoveryPreflightTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var appScope: CoroutineScope
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedReceivingState()
        repository = realBackupRepository(context)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun zeroEpochObservationOnlyBackup_isRejectedBeforeAnyReceivingStateChanges() = runBlocking {
        val before = snapshot()

        val failure = runCatching { repository.importBackup(invalidObservationOnlyBackup()) }.exceptionOrNull()

        assertTrue("the real import boundary must reject the file", failure is IllegalArgumentException)
        assertEquals("Invalid backup", failure?.message)
        assertEquals("no local raw, recovery, or progression state may change", before, snapshot())
    }

    @Test
    fun zeroEpochContributionBackup_isRejectedBeforeAnyReceivingStateChanges() = runBlocking {
        val before = snapshot()

        val failure = runCatching { repository.importBackup(invalidContributionBackup()) }.exceptionOrNull()

        assertTrue("the real import boundary must reject the file", failure is IllegalArgumentException)
        assertEquals("Invalid backup", failure?.message)
        assertEquals("no local raw, recovery, or progression state may change", before, snapshot())
    }

    @Test
    fun nonRepresentableObservationHighWater_isRejectedBeforeAnyReceivingStateChanges() = runBlocking {
        val before = snapshot()

        val failure = runCatching {
            repository.importBackup(observationBackup(highWater = Int.MAX_VALUE.toLong() + 1L))
        }.exceptionOrNull()

        assertTrue("the live detector cannot import this v3 high-water", failure is IllegalArgumentException)
        assertEquals("Invalid backup", failure?.message)
        assertEquals(before, snapshot())
    }

    @Test
    fun recoveryAccountDifferentFromBackupIdentity_isRejectedBeforeAnyReceivingStateChanges() = runBlocking {
        val before = snapshot()
        val mismatched = observationBackup(highWater = 60L).copy(
            identity = BackupIdentity(steamId64 = FOREIGN_ACCOUNT),
        )

        val failure = runCatching { repository.importBackup(mismatched) }.exceptionOrNull()

        assertTrue("inconsistent source account linkage must fail preflight", failure is IllegalArgumentException)
        assertEquals("Invalid backup", failure?.message)
        assertEquals(before, snapshot())
    }

    private suspend fun seedReceivingState() {
        database.gameDao().upsert(
            Game(
                appId = EXISTING_APP,
                name = "Existing local game",
                iconUrl = "",
                playtimeForever = 120,
                playtime2Weeks = 0,
                lastPlaytime = 120,
            ),
        )
        database.sessionDao().insert(
            Session(
                appId = EXISTING_APP,
                startAt = iso("2026-07-16T10:00:00Z"),
                endAt = iso("2026-07-16T11:00:00Z"),
                minutes = 60,
                open = false,
            ),
        )
        database.dailyProgressDao().upsert(
            DailyProgress(
                date = "2026-07-16",
                minutesPlayed = 60,
                goalMinutesPlayed = 15,
                questMet = true,
            ),
        )
        database.playerProfileDao().upsert(
            PlayerProfile(
                steamId = ACCOUNT,
                totalXp = 777L,
                level = 8,
                currentStreak = 3,
                longestStreak = 11,
                playtimeBackfilled = true,
            ),
        )
        database.playtimeRecoveryDao().upsertState(
            GameRecoveryState(
                account = ACCOUNT,
                appId = EXISTING_APP,
                baselineEpoch = 3,
                highWaterMinutes = 120,
                lastObservationAt = iso("2026-07-16T11:00:00Z"),
                lastObservationDate = "2026-07-16",
                lastObservationZone = "UTC",
                clockState = "VALID",
                updatedAt = iso("2026-07-16T11:00:00Z"),
            ),
        )
    }

    private suspend fun snapshot() = ReceivingSnapshot(
        games = database.gameDao().getAll(),
        sessions = database.sessionDao().getAll(),
        dailyProgress = database.dailyProgressDao().getAllOrdered(),
        profile = database.playerProfileDao().get(),
        recoveryStates = database.playtimeRecoveryDao().statesForAccount(ACCOUNT),
        recoveryContributions = database.playtimeRecoveryDao().contributionsForAccount(ACCOUNT),
    )

    private fun invalidObservationOnlyBackup() = BackupFile(
        formatVersion = 3,
        exportedAt = "2026-07-17T12:00:00Z",
        identity = BackupIdentity(steamId64 = ACCOUNT),
        ruleConfig = BackupRuleConfig(1, 100, 30, "ANY_GAME", 0, 5, 10, 20, 40, 80),
        games = listOf(BackupGame(appId = IMPORTED_APP, name = "Incoming game", isGoal = false, backfillMinutes = 0)),
        achievements = emptyList(),
        sessions = listOf(
            BackupSession(
                appId = IMPORTED_APP,
                startAt = "2026-07-17T10:00:00Z",
                endAt = "2026-07-17T11:00:00Z",
                minutes = 60,
                cloudContribution = BackupCloudContribution(
                    BackupContributionState.UNKNOWN,
                    BackupContributionState.UNKNOWN,
                ),
            ),
        ),
        dailyProgress = listOf(BackupDailyProgress("2026-07-17", 60, 15, true)),
        hltbData = emptyList(),
        librarySortPrefs = BackupLibrarySortPrefs(focus = "NAME", library = "PLAYTIME"),
        playerProfile = BackupPlayerProfile(
            totalXp = 99_999L,
            level = 99,
            currentStreak = 21,
            longestStreak = 99,
            playtimeBackfilled = false,
        ),
        computed = BackupComputed(emptyList(), emptyList()),
        recovery = BackupRecovery(
            observations = listOf(
                BackupRecoveryObservation(
                    account = ACCOUNT,
                    appId = IMPORTED_APP,
                    baselineEpoch = 0,
                    highWaterMinutes = 60,
                    lastObservationAt = "2026-07-17T12:00:00Z",
                    lastObservationDate = "2026-07-17",
                    lastObservationZone = "UTC",
                    clockState = "VALID",
                ),
            ),
            contributions = emptyList(),
            timedPlacements = emptyList(),
            unknownAllocations = emptyList(),
            recipes = emptyList(),
            replacements = emptyList(),
        ),
    )

    private fun observationBackup(highWater: Long): BackupFile {
        val backup = invalidObservationOnlyBackup()
        return backup.copy(
            recovery = backup.recovery!!.copy(
                observations = listOf(
                    backup.recovery.observations!!.single().copy(
                        baselineEpoch = 1L,
                        highWaterMinutes = highWater,
                    ),
                ),
            ),
        )
    }

    private fun invalidContributionBackup(): BackupFile {
        val backup = invalidObservationOnlyBackup()
        val observation = backup.recovery!!.observations!!.single().copy(baselineEpoch = 1L)
        return backup.copy(
            recovery = backup.recovery.copy(
                observations = listOf(observation),
                contributions = listOf(
                    BackupContribution(
                        contributionId = "zero-epoch-contribution",
                        account = ACCOUNT,
                        appId = IMPORTED_APP,
                        baselineEpoch = 0L,
                        fromMinutes = 0L,
                        toMinutes = 1L,
                        windowStartAt = "2026-07-17T10:00:00Z",
                        windowEndAt = "2026-07-17T12:00:00Z",
                        authorizedMinutes = 1L,
                        revision = 1L,
                        provenance = "RETROACTIVE_PLAY",
                        zone = "UTC",
                        observedZone = "UTC",
                    ),
                ),
                unknownAllocations = listOf(
                    BackupUnknownAllocation(
                        placementId = "zero-epoch-contribution:1:u:2026-07-17",
                        contributionId = "zero-epoch-contribution",
                        account = ACCOUNT,
                        appId = IMPORTED_APP,
                        revision = 1L,
                        date = "2026-07-17",
                        minutes = 1L,
                        method = "MULTI_DAY_ESTIMATE",
                        zone = "UTC",
                        windowStartAt = "2026-07-17T10:00:00Z",
                        windowEndAt = "2026-07-17T12:00:00Z",
                    ),
                ),
                replacements = emptyList(),
            ),
        )
    }

    private fun realBackupRepository(context: android.content.Context): BackupRepository {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val settingsDataStore = SettingsDataStore(context)
        val credentialsProvider = ConfiguredCredentials
        val settingsRepository: SettingsRepository = FakeSettingsRepository()
        val liveStatus = LiveStatusRepository(
            steamApi = unusedSteamApi(),
            gameDao = database.gameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            profileDao = database.playerProfileDao(),
            credentials = credentialsProvider,
            settings = settingsRepository,
            time = FixedTime,
            sessionEnds = PlaySessionEndPublisher(),
            presenceObserver = PresenceObserver { _, _ -> },
            diagnostics = null,
            scope = appScope,
        )
        val recoveryStore = DaoRecoveryBackupStore(database.playtimeRecoveryDao(), database.sessionDao())
        val transaction = RoomDatabaseTransactionScope(database)
        val exportMapper = BackupExportMapper(
            gameDao = database.gameDao(),
            achievementDao = database.achievementDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            hltbDataDao = database.hltbDataDao(),
            playerProfileDao = database.playerProfileDao(),
            collectionDao = database.collectionDao(),
            hiddenGameDao = database.hiddenGameDao(),
            excludedSharedGameDao = database.excludedSharedGameDao(),
            recoveryStore = recoveryStore,
            settings = settingsDataStore,
            credentials = credentialsProvider,
            time = FixedTime,
            transaction = transaction,
        )
        val updater = GamificationUpdater(
            database.sessionDao(),
            database.dailyProgressDao(),
            database.playerProfileDao(),
            database.hltbDataDao(),
            database.achievementDao(),
            database.gameDao(),
            database.hiddenGameDao(),
        )
        val mergeEngine = BackupMergeEngine(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            playerProfileDao = database.playerProfileDao(),
            collectionDao = database.collectionDao(),
            excludedSharedGameDao = database.excludedSharedGameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            gamificationUpdater = updater,
            time = FixedTime,
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = transaction,
            recoveryStore = recoveryStore,
            credentials = credentialsProvider,
        )
        val credentialsRepository = CredentialsRepository(
            EncryptedCredentialStore(context),
            unusedSteamApi(),
        )
        return BackupRepository(
            context = context,
            json = json,
            exportMapper = exportMapper,
            mergeEngine = mergeEngine,
            snapshotStore = SnapshotStore(context, json),
            settings = settingsDataStore,
            credentials = credentialsRepository,
            time = FixedTime,
            syncScheduler = SyncScheduler(context, appScope),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            liveStatusRepository = liveStatus,
            syncCoordinator = SteamSyncCoordinator(),
        )
    }

    private fun unusedSteamApi(): SteamApi = Proxy.newProxyInstance(
        SteamApi::class.java.classLoader,
        arrayOf(SteamApi::class.java),
    ) { _, method, _ -> error("Unexpected Steam API call in import-preflight test: ${method.name}") } as SteamApi

    private fun iso(text: String): Long = java.time.Instant.parse(text).toEpochMilli()

    private data class ReceivingSnapshot(
        val games: List<Game>,
        val sessions: List<Session>,
        val dailyProgress: List<DailyProgress>,
        val profile: PlayerProfile?,
        val recoveryStates: List<GameRecoveryState>,
        val recoveryContributions: List<com.example.backlogium.data.local.entity.PlaytimeContribution>,
    )

    private object ConfiguredCredentials : CredentialsProvider {
        override suspend fun currentCredentials() = CredentialsState.Configured(apiKey = "unused", steamId = ACCOUNT)
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = java.time.Instant.parse("2026-07-17T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-17")
    }

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val FOREIGN_ACCOUNT = "76561198000000001"
        const val EXISTING_APP = 440L
        const val IMPORTED_APP = 730L
    }
}
