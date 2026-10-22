package com.example.backlogium.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.backlogium.data.backup.BackupExportMapper
import com.example.backlogium.data.backup.BackupMergeEngine
import com.example.backlogium.data.backup.DaoRecoveryBackupStore
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.RecoveryReadStatusStore
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.InMemoryProgressMarksStore
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.ProgressTransitionCoordinator
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-backed #181 acceptance flow. The Steam totals below are injected fixtures; this test
 * makes no Steam/API request and is not independent live Steam evidence. Closing and reopening the
 * file-backed Room database simulates a restart between completed recovery and a later retry; it
 * does not kill the instrumentation process.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryOfflineRestartDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun injectedOfflineIncreasesSurviveRestartRetryHistoryAndBackupRestore() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val context = appContext.createDeviceProtectedStorageContext()
        val suffix = System.nanoTime().toString().takeLast(9)
        val account = "7656119${suffix.padStart(10, '0')}" // Synthetic test-only SteamID.
        val sourceName = "recovery_device_source_$suffix.db"
        val restoredName = "recovery_device_restore_$suffix.db"
        context.deleteDatabase(sourceName)
        context.deleteDatabase(restoredName)

        val monday = atUtc("2026-07-20")
        val tuesday = atUtc("2026-07-21")
        val wednesday = atUtc("2026-07-22")
        val friday = atUtc("2026-07-24")
        val multiDayApp = 440L
        val singleDayApp = 570L
        val config = RuleConfig(xpPerMinute = 1, questThresholdMin = 26)
        val clock = FixtureTime(monday)
        val statusStore = RecoveryReadStatusStore(context)
        var source = openDatabase(context, sourceName)
        var restored: BacklogiumDatabase? = null

        try {
            source.openHelper.writableDatabase
            source.gameDao().upsertAll(
                listOf(
                    game(multiDayApp, "Multi-day recovery", 500, monday),
                    game(singleDayApp, "Single-day recovery", 500, tuesday),
                ),
            )
            source.playerProfileDao().upsert(
                PlayerProfile(steamId = account, lastSyncAt = monday, level = 1),
            )
            statusStore.successfulRead(
                account = account,
                observedAt = monday,
                baselineEstablishedAppIds = setOf(multiDayApp, singleDayApp),
            )

            // Model a local offline play cue. No cloud reader or Steam client is called here.
            statusStore.observedPlay(account, multiDayApp, tuesday + 60_000L, force = true)
            assertEquals(
                com.example.backlogium.domain.PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM,
                statusStore.observe(account).first(),
            )

            clock.now = wednesday
            val singleDay = commit(
                database = source,
                clock = clock,
                account = account,
                config = config,
                observedAt = wednesday,
                games = listOf(observed(singleDayApp, "Single-day recovery", 570)),
            )
            assertEquals(70, singleDay.playedDeltaByAppId[singleDayApp])
            statusStore.successfulRead(account, wednesday, creditedIncreaseAppIds = setOf(singleDayApp))
            assertEquals(
                com.example.backlogium.domain.PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM,
                statusStore.observe(account).first(),
            )

            clock.now = friday
            val multiDay = commit(
                database = source,
                clock = clock,
                account = account,
                config = config,
                observedAt = friday,
                games = listOf(observed(multiDayApp, "Multi-day recovery", 605)),
            )
            assertEquals(105, multiDay.playedDeltaByAppId[multiDayApp])
            statusStore.successfulRead(account, friday, creditedIncreaseAppIds = setOf(multiDayApp))
            assertEquals(
                com.example.backlogium.domain.PlaytimeRecoveryStatus.KNOWN_GOOD,
                statusStore.observe(account).first(),
            )
            assertRawRecovery(source, account, multiDayApp, singleDayApp)
            gamificationUpdater(source).recompute(
                today = clock.today(), source = RecomputeSource.SYNC, config = config,
            )

            // Reopen the on-disk DB after the successful sync, then retry both cumulative totals.
            // The status store is also reconstructed from its persisted data.
            source.close()
            source = openDatabase(context, sourceName)
            assertEquals(
                com.example.backlogium.domain.PlaytimeRecoveryStatus.KNOWN_GOOD,
                RecoveryReadStatusStore(context).observe(account).first(),
            )

            val xpBeforeRetry = source.playerProfileDao().get()!!.totalXp
            val retry = commit(
                database = source,
                clock = clock,
                account = account,
                config = config,
                observedAt = friday,
                games = listOf(
                    observed(multiDayApp, "Multi-day recovery", 605),
                    observed(singleDayApp, "Single-day recovery", 570),
                ),
            )
            assertFalse("replaying both cumulative totals must authorize no new minutes", retry.recordedPlay)
            gamificationUpdater(source).recompute(
                today = clock.today(), source = RecomputeSource.SYNC, config = config,
            )
            assertRawRecovery(source, account, multiDayApp, singleDayApp)

            val profile = checkNotNull(source.playerProfileDao().get())
            assertTrue("recovered play contributes XP", profile.totalXp > 0L)
            assertEquals("retry does not award XP twice", xpBeforeRetry, profile.totalXp)
            assertEquals("four recovered quest dates repair the streak", 4, profile.currentStreak)
            listOf("2026-07-21", "2026-07-22", "2026-07-23", "2026-07-24").forEach { date ->
                assertTrue("quest should be met on $date", source.dailyProgressDao().getByDate(date)!!.questMet)
            }

            val sourceRepository = sessionRepository(source, clock, context)
            assertEquals(
                listOf(27L, 96L, 26L, 26L),
                sourceRepository.datedCreditedTotalsBetween(
                    ZoneOffset.UTC, LocalDate.parse("2026-07-21"), LocalDate.parse("2026-07-25"),
                ).first().map { it.minutes },
            )
            assertFridayEstimateVisible(sourceRepository, source, multiDayApp)

            val exported = exportMapper(source, context, clock, account).buildExport()
            assertEquals(3, exported.formatVersion)
            val exportedRecovery = checkNotNull(exported.recovery)
            assertEquals(2, checkNotNull(exportedRecovery.contributions).size)
            assertEquals(5, checkNotNull(exportedRecovery.unknownAllocations).size)

            restored = openDatabase(context, restoredName)
            restored!!.openHelper.writableDatabase
            val restoreEngine = mergeEngine(restored!!, clock, account)
            restoreEngine.merge(exported, config)
            assertRawRecovery(restored!!, account, multiDayApp, singleDayApp)
            assertEquals(4, restored!!.playerProfileDao().get()!!.currentStreak)
            val restoredRecovery = checkNotNull(
                exportMapper(restored!!, context, clock, account).buildExport().recovery,
            )
            assertEquals(checkNotNull(exportedRecovery.contributions), checkNotNull(restoredRecovery.contributions))
            assertEquals(
                checkNotNull(exportedRecovery.unknownAllocations),
                checkNotNull(restoredRecovery.unknownAllocations),
            )
            val restoredFridayAllocation = sessionRepository(restored!!, clock, context)
                .datedAllocationsBetween(LocalDate.parse("2026-07-21"), LocalDate.parse("2026-07-24"))
                .first().single { it.appId == multiDayApp && it.date == LocalDate.parse("2026-07-24") }
            assertEquals(26, restoredFridayAllocation.minutes)
            assertEquals(105, restoredFridayAllocation.windowTotalMinutes)
        } finally {
            runCatching { source.close() }
            runCatching { restored?.close() }
            context.deleteDatabase(sourceName)
            context.deleteDatabase(restoredName)
        }
    }

    private suspend fun assertFridayEstimateVisible(
        repository: SessionRepository,
        database: BacklogiumDatabase,
        appId: Long,
    ) {
        val friday = LocalDate.parse("2026-07-24")
        val allocations = repository.datedAllocationsBetween(
            LocalDate.parse("2026-07-21"), friday,
        ).first()
        val leaf = allocations.single { it.appId == appId && it.date == friday }.toHistoryAllocationUi()
        val progress = checkNotNull(database.dailyProgressDao().getByDate(friday.toString()))
        val game = checkNotNull(database.gameDao().getById(appId))
        val gameGroup = HistoryGameGroup(
            appId = appId,
            name = game.name,
            iconUrl = game.iconUrl,
            minutesPlayed = leaf.minutes,
            sessions = emptyList(),
            allocations = listOf(leaf),
        )
        val day = HistoryDayGroup(
            date = friday.toString(),
            minutesPlayed = progress.minutesPlayed,
            goalMinutesPlayed = progress.goalMinutesPlayed,
            questMet = progress.questMet,
            games = listOf(gameGroup),
            achievements = HistoryAchievements(emptyList(), 0),
            estimatedMinutes = leaf.minutes,
        )
        composeRule.setContent {
            BacklogiumTheme {
                HistoryContent(
                    state = HistoryUiState(
                        loading = false,
                        configured = true,
                        today = friday.toString(),
                        days = listOf(day),
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_HISTORY_DAY_ESTIMATE_HELP).performScrollTo().performClick()
        composeRule.onNodeWithText("26 mins of that window fall on this day", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("recovered 1 hr 45 mins in total", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Got it").performClick()
        composeRule.onNode(hasContentDescription(game.name, substring = true))
            .performScrollTo().performClick()
        composeRule.onNodeWithTag(historyAllocationTestTag(leaf.placementId)).assertIsDisplayed()
        composeRule.onNode(
            hasTestTag(TAG_HISTORY_ESTIMATE_HELP) and
                androidx.compose.ui.test.hasAnyAncestor(hasTestTag(historyAllocationTestTag(leaf.placementId))),
        ).assertIsDisplayed()
    }

    private suspend fun assertRawRecovery(
        database: BacklogiumDatabase,
        account: String,
        multiDayApp: Long,
        singleDayApp: Long,
    ) {
        val byDate = database.dailyProgressDao().getAllOrdered().associate { it.date to it.minutesPlayed }
        assertEquals(27, byDate["2026-07-21"])
        assertEquals(96, byDate["2026-07-22"])
        assertEquals(26, byDate["2026-07-23"])
        assertEquals(26, byDate["2026-07-24"])
        assertEquals(175, byDate.values.sum())
        assertTrue("unknown-time recovery must not invent timestamped sessions", database.sessionDao().getAll().isEmpty())

        val allocations = database.playtimeRecoveryDao().activeAllocationsForAccount(account)
        val multi = allocations.filter { it.appId == multiDayApp }.sortedBy { it.date }
        val single = allocations.filter { it.appId == singleDayApp }
        assertEquals(listOf(27L, 26L, 26L, 26L), multi.map { it.minutes })
        assertEquals(listOf("2026-07-21", "2026-07-22", "2026-07-23", "2026-07-24"), multi.map { it.date })
        assertEquals(listOf(70L), single.map { it.minutes })
        assertEquals("2026-07-22", single.single().date)
    }

    private fun sessionRepository(
        database: BacklogiumDatabase,
        clock: FixtureTime,
        context: android.content.Context,
    ): SessionRepository {
        val hidden = HiddenGamesRepository(
            database.hiddenGameDao(), database.gameDao(), database.gameGenreCacheDao(), clock,
        )
        return SessionRepository(
            sessionDao = database.sessionDao(),
            hiddenGamesRepository = hidden,
            time = clock,
            playerProfileDao = database.playerProfileDao(),
            recoveryReadStatusStore = RecoveryReadStatusStore(context),
            database = database,
        )
    }

    private fun exportMapper(
        database: BacklogiumDatabase,
        context: android.content.Context,
        clock: FixtureTime,
        account: String,
    ) = BackupExportMapper(
        gameDao = database.gameDao(),
        achievementDao = database.achievementDao(),
        sessionDao = database.sessionDao(),
        dailyProgressDao = database.dailyProgressDao(),
        hltbDataDao = database.hltbDataDao(),
        playerProfileDao = database.playerProfileDao(),
        collectionDao = database.collectionDao(),
        excludedSharedGameDao = database.excludedSharedGameDao(),
        hiddenGameDao = database.hiddenGameDao(),
        recoveryStore = DaoRecoveryBackupStore(database.playtimeRecoveryDao(), database.sessionDao()),
        settings = SettingsDataStore(context),
        credentials = FixtureCredentials(account),
        time = clock,
        transaction = RoomDatabaseTransactionScope(database),
    )

    private fun mergeEngine(
        database: BacklogiumDatabase,
        clock: FixtureTime,
        account: String,
    ) = BackupMergeEngine(
        gameDao = database.gameDao(),
        sessionDao = database.sessionDao(),
        dailyProgressDao = database.dailyProgressDao(),
        hltbDataDao = database.hltbDataDao(),
        achievementDao = database.achievementDao(),
        playerProfileDao = database.playerProfileDao(),
        collectionDao = database.collectionDao(),
        excludedSharedGameDao = database.excludedSharedGameDao(),
        hiddenGameDao = database.hiddenGameDao(),
        gamificationUpdater = gamificationUpdater(database),
        time = clock,
        derivedStateWrites = DerivedStateWriteCoordinator(),
        transaction = RoomDatabaseTransactionScope(database),
        recoveryStore = DaoRecoveryBackupStore(database.playtimeRecoveryDao(), database.sessionDao()),
        credentials = FixtureCredentials(account),
    )

    private fun gamificationUpdater(database: BacklogiumDatabase) = GamificationUpdater(
        sessionDao = database.sessionDao(),
        dailyProgressDao = database.dailyProgressDao(),
        playerProfileDao = database.playerProfileDao(),
        hltbDataDao = database.hltbDataDao(),
        achievementDao = database.achievementDao(),
        gameDao = database.gameDao(),
        hiddenGameDao = database.hiddenGameDao(),
        progressMarksStore = InMemoryProgressMarksStore(),
        transitionCoordinator = ProgressTransitionCoordinator(),
    )

    private suspend fun commit(
        database: BacklogiumDatabase,
        clock: FixtureTime,
        account: String,
        config: RuleConfig,
        observedAt: Long,
        games: List<PlaytimeObservationCommitter.ObservedGame>,
    ) = database.withTransaction {
        PlaytimeObservationCommitter(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            profileDao = database.playerProfileDao(),
            hiddenGameDao = database.hiddenGameDao(),
            differ = SessionDiffer(),
            time = clock,
            sessionActionWriter = SessionActionWriter(
                sessionDao = database.sessionDao(),
                dailyProgressDao = database.dailyProgressDao(),
                hiddenGameDao = database.hiddenGameDao(),
                time = clock,
            ),
            recoveryAdapter = PlaytimeRecoveryCommitAdapter(
                database.playtimeRecoveryDao(), database.sessionDao(),
            ),
        ).commit(
            observed = games,
            observedPlayAt = observedAt,
            syncedAt = observedAt,
            expectedAccount = account,
            ruleConfig = config,
        )
    }

    private fun observed(appId: Long, name: String, playtime: Int) =
        PlaytimeObservationCommitter.ObservedGame(appId, name, "", playtime, 0)

    private fun game(appId: Long, name: String, playtime: Int, syncedAt: Long) = Game(
        appId = appId,
        name = name,
        iconUrl = "",
        playtimeForever = playtime,
        playtime2Weeks = 0,
        lastPlaytime = playtime,
        lastSyncedAt = syncedAt,
    )

    private fun openDatabase(context: android.content.Context, name: String) =
        Room.databaseBuilder(context, BacklogiumDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun atUtc(date: String): Long =
        LocalDate.parse(date).atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private class FixtureTime(var now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun today(): LocalDate = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
    }

    private class FixtureCredentials(private val account: String) : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured =
            CredentialsState.Configured(apiKey = "instrumentation-fixture", steamId = account)
    }
}
