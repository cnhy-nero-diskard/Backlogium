package com.example.backlogium.domain

import com.example.backlogium.test.SettingsDataStoreRule
import org.junit.Rule

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.data.local.entity.CloudHistoricalStates
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.repo.CloudPresenceRefilingBackup
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CloudPresencePlaytimeRefilingUseCaseTest {
    @get:Rule val settingsFixture = SettingsDataStoreRule()


    @Test
    fun historicalApplyAtomicallyTransfersImportedMinutesAndReplaysFromRoomJournal() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settingsDataStore = settingsFixture.create()
        val realSettings = DataStoreSettingsRepository(settingsDataStore)
        val marks = InMemoryProgressMarksStore(ProgressMarks(lastCelebratedLevel = 1, initialized = true))
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
        val failingSettings = FailOnceOnHistoricalBackup(realSettings)
        val derivedStateWrites = DerivedStateWriteCoordinator()
        fun useCase(settings: SettingsRepository) = CloudPresencePlaytimeRefilingUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            cloudHistoricalDao = database.cloudHistoricalDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = derivedStateWrites,
            transaction = RoomDatabaseTransactionScope(database),
        )
        val historyReset = PlaytimeBackfillUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            playerProfileDao = database.playerProfileDao(),
            settings = settingsDataStore,
            gamificationUpdater = updater,
            time = FixedTime,
            derivedStateWrites = derivedStateWrites,
        )
        val fromAt = utc("2026-07-25T00:00:00Z")
        val throughAt = utc("2026-07-26T12:00:00Z")
        val cutoffAt = utc("2026-07-26T11:00:00Z")
        val operation = CloudHistoricalOperation(
            operationId = "historical-apply-test",
            account = "76561198000000001",
            readerGeneration = 5,
            endpointIdentity = "https://presence.example.test/readPresence",
            startChoice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE.name,
            zoneId = "UTC",
            selectedStartAt = fromAt,
            fromAt = fromAt,
            throughAt = throughAt,
            confirmedCutoffAt = cutoffAt,
            pagesFetched = 1,
            transitionsFetched = 2,
            coveredStartAt = fromAt,
            coveredEndAt = throughAt,
            acquisitionComplete = true,
            state = CloudHistoricalStates.COMPLETE,
            createdAt = 1,
            updatedAt = 1,
        )

        try {
            realSettings.clearCloudPresenceRefiling()
            database.gameDao().upsert(
                Game(
                    appId = GAME,
                    name = "Portal",
                    iconUrl = "",
                    playtimeForever = 22,
                    playtime2Weeks = 0,
                    lastPlaytime = 22,
                    backfillMinutes = 12,
                    source = GameSource.STEAM_OWNED,
                ),
            )
            database.playerProfileDao().upsert(PlayerProfile(playtimeBackfilled = true))
            database.sessionDao().insert(
                Session(
                    appId = GAME,
                    startAt = utc("2026-07-25T23:50:00Z"),
                    endAt = utc("2026-07-26T00:10:00Z"),
                    minutes = 10,
                    open = false,
                    recoveredSharedPlay = RecoveredSharedPlayState.PARTIAL,
                ),
            )
            check(database.cloudHistoricalDao().insertOperation(operation) != -1L)
            database.cloudHistoricalDao().insertInterval(
                CloudHistoricalInterval(
                    operationId = operation.operationId,
                    account = operation.account,
                    readerGeneration = operation.readerGeneration,
                    endpointIdentity = operation.endpointIdentity,
                    appId = GAME,
                    startAt = utc("2026-07-26T00:00:00Z"),
                    endAt = utc("2026-07-26T00:10:00Z"),
                    ongoing = false,
                    coverage = "CONTINUOUS",
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                    gameName = "Portal",
                    windowStart = fromAt,
                ),
            )
            database.cloudHistoricalDao().insertInterval(
                CloudHistoricalInterval(
                    operationId = operation.operationId,
                    account = operation.account,
                    readerGeneration = operation.readerGeneration,
                    endpointIdentity = operation.endpointIdentity,
                    appId = GAME,
                    startAt = utc("2026-07-25T22:00:00Z"),
                    endAt = utc("2026-07-25T22:20:00Z"),
                    ongoing = false,
                    coverage = "LEGACY_TRANSITIONS",
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                    gameName = "Portal",
                    windowStart = fromAt,
                ),
            )
            updater.recompute(FixedTime.today(), RecomputeSource.BACKFILL)
            val originalSession = database.sessionDao().getAll().single()
            val profileBefore = database.playerProfileDao().get()!!
            val gameBefore = database.gameDao().getById(GAME)!!
            val trackedBefore = database.sessionDao().trackedMinutesByGame().single().minutes
            val creditedBefore = trackedBefore + gameBefore.backfillMinutes

            try {
                useCase(failingSettings).applyHistorical(operation)
                fail("Expected the simulated process interruption after the Room commit")
            } catch (expected: RuntimeException) {
                assertEquals("injected historical marker failure", expected.message)
            }

            val journalAfterInterruption = database.cloudHistoricalDao().journal(operation.operationId)!!
            assertEquals(CloudHistoricalStates.JOURNAL_APPLY_COMMITTED, journalAfterInterruption.state)
            assertFalse(realSettings.cloudPresenceRefilingApplied.first())
            assertNull(realSettings.cloudPresenceRefilingReceipt.first())
            assertEquals(22, database.gameDao().getById(GAME)!!.playtimeForever)
            assertEquals(22, database.sessionDao().trackedMinutesByGame().single().minutes)
            assertEquals(0, database.gameDao().getById(GAME)!!.backfillMinutes)
            assertEquals(22, database.sessionDao().getAll().sumOf { it.minutes })

            // Simulate a later Steam response during the crash window. Recovery must finish the
            // recorded 12-minute transfer rather than recomputing it from this newer total.
            database.gameDao().getById(GAME)!!.let { latest ->
                database.gameDao().upsert(latest.copy(playtimeForever = 35, lastPlaytime = 35))
            }

            val applied = useCase(realSettings).applyHistorical(operation)

            assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
            assertEquals(1, applied.sessionsRefiled)
            assertTrue(realSettings.cloudPresenceRefilingApplied.first())
            assertEquals(PlaytimeBackfillResetResult.BLOCKED_BY_CLOUD_TRANSFER, historyReset.reset())
            assertTrue(database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(0, database.gameDao().getById(GAME)!!.backfillMinutes)
            val gameAfter = database.gameDao().getById(GAME)!!
            val sessionsAfter = database.sessionDao().getAll()
            val trackedAfter = database.sessionDao().trackedMinutesByGame().single().minutes
            assertEquals(35, gameAfter.playtimeForever)
            assertEquals(creditedBefore, trackedAfter + gameAfter.backfillMinutes)
            assertEquals(trackedBefore + 12, trackedAfter)
            assertEquals(gameBefore.backfillMinutes - 12, gameAfter.backfillMinutes)
            assertEquals(creditedBefore, sessionsAfter.sumOf { it.minutes } + gameAfter.backfillMinutes)
            assertEquals(profileBefore.totalXp, database.playerProfileDao().get()!!.totalXp)
            assertEquals(profileBefore.level, database.playerProfileDao().get()!!.level)
            assertEquals(2, sessionsAfter.size)
            assertTrue(
                sessionsAfter.any { session ->
                    session.startAt == utc("2026-07-25T22:08:00Z") &&
                        session.endAt == utc("2026-07-25T22:20:00Z") &&
                        session.minutes == 12 &&
                        session.timingInformedSteamPlay == TimingInformedSteamPlayState.FULL &&
                        session.recoveredSharedPlay == RecoveredSharedPlayState.NONE
                },
            )
            assertEquals(CloudHistoricalStates.JOURNAL_APPLIED,
                database.cloudHistoricalDao().journal(operation.operationId)!!.state)
            assertEquals(CloudHistoricalStates.APPLIED,
                database.cloudHistoricalDao().operation(operation.operationId)!!.state)
            assertEquals(2, database.cloudHistoricalDao().intervals(operation.operationId).size)
            val receipt = realSettings.cloudPresenceRefilingReceipt.first()!!
            assertEquals(operation.selectedStartAt, receipt.selectedStartAt)
            assertEquals(operation.fromAt, receipt.effectiveStartAt)
            assertEquals(operation.throughAt, receipt.throughAt)
            assertEquals(operation.coveredStartAt, receipt.coveredStartAt)
            assertEquals(operation.coveredEndAt, receipt.coveredEndAt)
            assertEquals(cutoffAt, receipt.confirmedCutoffAt)
            assertEquals(operation.startChoice, receipt.startChoice)
            assertEquals(operation.zoneId, receipt.zoneId)
            assertEquals(operation.pagesFetched, receipt.pagesFetched)
            assertEquals(operation.transitionsFetched, receipt.transitionsFetched)
            assertEquals(1, receipt.sessionsRefiled)
            assertEquals(12, receipt.transferredMinutesByAppId.single().minutes)
            assertEquals(0, receipt.remainingImportedMinutesByAppId.single().minutes)
            assertEquals(1, receipt.createdSessionIds.size)
            assertEquals(
                receipt.createdSessionIds.toSet(),
                realSettings.cloudPresenceRefilingBackup()!!.createdSessionIds,
            )

            val sessionsBeforeReplay = database.sessionDao().getAll()
            val replay = useCase(realSettings).applyHistorical(operation)
            assertEquals(CloudPresenceRefilingOperation.NO_OP, replay.operation)
            assertEquals(sessionsBeforeReplay, database.sessionDao().getAll())
            assertEquals(0, database.gameDao().getById(GAME)!!.backfillMinutes)

            val laterStart = utc("2026-07-27T10:00:00Z")
            val laterSessionId = database.sessionDao().insert(
                Session(
                    appId = GAME,
                    startAt = laterStart,
                    endAt = utc("2026-07-27T10:20:00Z"),
                    minutes = 20,
                    open = false,
                ),
            )
            database.gameDao().getById(GAME)!!.let { latest ->
                // Simulate independent changes after apply. Undo must add its recorded 12-minute
                // delta to the current balance, not overwrite it with the old absolute balance.
                database.gameDao().upsert(
                    latest.copy(
                        name = "Portal renamed",
                        playtimeForever = 44,
                        lastPlaytime = 44,
                        backfillMinutes = 5,
                    ),
                )
            }
            val reverseSettings = FailAroundHistoricalReverseCleanup(realSettings)

            try {
                useCase(FailOnHistoricalReverseRecompute(realSettings)).reverse()
                fail("Expected the simulated interruption after the Room reversal commit")
            } catch (expected: RuntimeException) {
                assertEquals("injected failure during historical reverse recompute", expected.message)
            }

            assertEquals(CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED,
                database.cloudHistoricalDao().journal(operation.operationId)!!.state)
            assertEquals(CloudHistoricalStates.REVERSING,
                database.cloudHistoricalDao().operation(operation.operationId)!!.state)
            assertTrue(realSettings.cloudPresenceRefilingApplied.first())
            val afterRoomReverse = database.sessionDao().getAll()
            assertEquals(2, afterRoomReverse.size)
            assertTrue(afterRoomReverse.any { it == originalSession })
            assertTrue(afterRoomReverse.any { it.id == laterSessionId && it.minutes == 20 })
            val gameAfterReverse = database.gameDao().getById(GAME)!!
            assertEquals("Portal renamed", gameAfterReverse.name)
            assertEquals(44, gameAfterReverse.playtimeForever)
            assertEquals(17, gameAfterReverse.backfillMinutes)
            assertEquals(47, afterRoomReverse.sumOf { it.minutes } + gameAfterReverse.backfillMinutes)

            try {
                useCase(reverseSettings).reverse()
                fail("Expected the simulated interruption before DataStore cleanup")
            } catch (expected: RuntimeException) {
                assertEquals("injected failure before historical cleanup", expected.message)
            }
            assertEquals(CloudHistoricalStates.JOURNAL_REVERSED,
                database.cloudHistoricalDao().journal(operation.operationId)!!.state)
            assertEquals(CloudHistoricalStates.REVERSED,
                database.cloudHistoricalDao().operation(operation.operationId)!!.state)
            assertTrue(realSettings.cloudPresenceRefilingApplied.first())

            try {
                useCase(reverseSettings).reverse()
                fail("Expected the simulated interruption after DataStore cleanup")
            } catch (expected: RuntimeException) {
                assertEquals("injected failure after historical cleanup", expected.message)
            }
            assertFalse(realSettings.cloudPresenceRefilingApplied.first())
            assertEquals(
                CloudPresenceRefilingOperation.NO_OP,
                useCase(realSettings).reverse().operation,
            )
            assertEquals(17, database.gameDao().getById(GAME)!!.backfillMinutes)
            assertEquals(afterRoomReverse, database.sessionDao().getAll())
            assertEquals(PlaytimeBackfillResetResult.RESET, historyReset.reset())
            assertEquals(0, database.gameDao().getById(GAME)!!.backfillMinutes)
            assertFalse(database.playerProfileDao().get()!!.playtimeBackfilled)
            assertEquals(afterRoomReverse, database.sessionDao().getAll())
        } finally {
            realSettings.clearCloudPresenceRefiling()
            database.close()
        }
    }

    @Test
    fun historicalZeroChangeApplyStillPersistsReceiptAndRetainsCompletedStage() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = DataStoreSettingsRepository(
            settingsFixture.create(),
        )
        val updater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            progressMarksStore = InMemoryProgressMarksStore(),
        )
        val useCase = CloudPresencePlaytimeRefilingUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            cloudHistoricalDao = database.cloudHistoricalDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = RoomDatabaseTransactionScope(database),
        )
        val fromAt = utc("2026-07-01T00:00:00Z")
        val throughAt = utc("2026-07-26T12:00:00Z")
        val operation = CloudHistoricalOperation(
            operationId = "historical-zero-change-test",
            account = "76561198000000001",
            readerGeneration = 6,
            endpointIdentity = "https://presence.example.test/readPresence",
            startChoice = CloudPresenceHistoricalStartChoice.RECENT_31_DAYS.name,
            zoneId = "UTC",
            selectedStartAt = fromAt,
            fromAt = fromAt,
            throughAt = throughAt,
            coveredStartAt = null,
            coveredEndAt = null,
            pagesFetched = 1,
            transitionsFetched = 0,
            acquisitionComplete = true,
            state = CloudHistoricalStates.COMPLETE,
            createdAt = 1,
            updatedAt = 1,
        )

        try {
            settings.clearCloudPresenceRefiling()
            check(database.cloudHistoricalDao().insertOperation(operation) != -1L)

            val result = useCase.applyHistorical(operation)

            assertEquals(CloudPresenceRefilingOperation.APPLIED, result.operation)
            assertEquals(0, result.sessionsRefiled)
            assertTrue(result.datesAffected.isEmpty())
            assertTrue(settings.cloudPresenceRefilingApplied.first())
            val receipt = settings.cloudPresenceRefilingReceipt.first()!!
            assertEquals(operation.operationId, receipt.operationId)
            assertEquals(fromAt, receipt.selectedStartAt)
            assertEquals(fromAt, receipt.effectiveStartAt)
            assertEquals(throughAt, receipt.throughAt)
            assertNull(receipt.coveredStartAt)
            assertNull(receipt.coveredEndAt)
            assertEquals(0, receipt.sessionsRefiled)
            assertTrue(receipt.createdSessionIds.isEmpty())
            assertTrue(receipt.transferredMinutesByAppId.isEmpty())
            assertTrue(receipt.remainingImportedMinutesByAppId.isEmpty())
            assertEquals(CloudHistoricalStates.APPLIED,
                database.cloudHistoricalDao().operation(operation.operationId)!!.state)
            assertEquals(CloudHistoricalStates.JOURNAL_APPLIED,
                database.cloudHistoricalDao().journal(operation.operationId)!!.state)
        } finally {
            settings.clearCloudPresenceRefiling()
            database.close()
        }
    }

    @Test
    fun applyAndReverseRestoresTheLedgerExactlyAndCanBeOfferedAgain() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = DataStoreSettingsRepository(
            settingsFixture.create(),
        )
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 1,
                initialized = true,
                pendingQuestDates = setOf(LocalDate.of(2026, 7, 24)),
            ),
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
        val useCase = CloudPresencePlaytimeRefilingUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            cloudHistoricalDao = database.cloudHistoricalDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = RoomDatabaseTransactionScope(database),
        )
        val originalStart = utc("2026-07-25T23:50:00Z")
        val originalEnd = utc("2026-07-26T00:10:00Z")
        val presenceStart = utc("2026-07-26T00:00:00Z")
        val presenceEnd = originalEnd

        try {
            settings.clearCloudPresenceRefiling()
            database.gameDao().upsert(
                Game(
                    appId = GAME,
                    name = "Portal",
                    iconUrl = "",
                    playtimeForever = 130,
                    playtime2Weeks = 0,
                    lastPlaytime = 130,
                    backfillMinutes = 60,
                    isGoal = true,
                    source = GameSource.STEAM_OWNED,
                ),
            )
            database.sessionDao().insert(
                Session(
                    appId = GAME,
                    startAt = originalStart,
                    endAt = originalEnd,
                    minutes = 30,
                    open = false,
                    recoveredSharedPlay = RecoveredSharedPlayState.PARTIAL,
                    timingInformedSteamPlay = TimingInformedSteamPlayState.PARTIAL,
                ),
            )
            database.playerProfileDao().upsert(PlayerProfile(longestStreak = 7))
            database.dailyProgressDao().upsert(
                DailyProgress("2026-07-24", minutesPlayed = 99, goalMinutesPlayed = 40, questMet = true),
            )
            database.dailyProgressDao().upsert(
                DailyProgress("2026-07-25", minutesPlayed = 30, goalMinutesPlayed = 30, questMet = true),
            )

            val beforeSessions = database.sessionDao().getAll()
            val beforeDays = database.dailyProgressDao().getAllOrdered()
            val beforeGame = database.gameDao().getAll().single()
            val beforeMinutes = beforeSessions.sumOf { it.minutes }
            val intervals = listOf(
                CloudPresenceInterval(
                    appId = GAME,
                    gameName = "Portal",
                    startAt = presenceStart,
                    endAt = presenceEnd,
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            )
            val range = CloudPresenceHistoricalSelection(
                choice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
                selectedStartAt = presenceStart,
                effectiveStartAt = presenceStart,
                throughAt = presenceEnd,
                zoneId = "UTC",
            )

            val applied = useCase.apply(intervals, range)

            assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
            assertEquals(1, applied.sessionsRefiled)
            assertEquals(setOf("2026-07-25", "2026-07-26"), applied.datesAffected)
            assertEquals(beforeMinutes, database.sessionDao().getAll().sumOf { it.minutes })
            // No pre-data cutoff was confirmed, so the date-only re-file leaves imported play alone.
            assertEquals(beforeGame, database.gameDao().getAll().single())
            assertNotEquals(beforeSessions, database.sessionDao().getAll())
            assertEquals(
                RecoveredSharedPlayState.PARTIAL,
                database.sessionDao().getAll().single().recoveredSharedPlay,
            )
            assertEquals(
                TimingInformedSteamPlayState.FULL,
                database.sessionDao().getAll().single().timingInformedSteamPlay,
            )
            assertTrue(database.dailyProgressDao().getByDate("2026-07-26") != null)
            assertEquals(setOf(LocalDate.of(2026, 7, 24)), marks.read().pendingQuestDates)
            assertEquals(7, database.playerProfileDao().get()!!.longestStreak)

            val repeated = useCase.apply(intervals, range)
            assertEquals(CloudPresenceRefilingOperation.NO_OP, repeated.operation)

            val reversed = useCase.reverse()

            assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
            assertEquals(beforeSessions, database.sessionDao().getAll())
            assertEquals(beforeDays, database.dailyProgressDao().getAllOrdered())
            assertEquals(beforeGame, database.gameDao().getAll().single())
            assertEquals(beforeMinutes, database.sessionDao().getAll().sumOf { it.minutes })
            assertFalse(database.dailyProgressDao().getByDate("2026-07-26") != null)
            assertFalse(settings.cloudPresenceRefilingApplied.first())

            assertEquals(
                CloudPresenceRefilingOperation.NO_OP,
                useCase.reverse().operation,
            )
            assertEquals(
                CloudPresenceRefilingOperation.APPLIED,
                useCase.apply(intervals).operation,
            )
        } finally {
            settings.clearCloudPresenceRefiling()
            database.close()
        }
    }

    @Test
    fun applyResumesAfterInterruptionWithoutLosingTheBackup() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val realSettings = DataStoreSettingsRepository(
            settingsFixture.create(),
        )
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 1,
                initialized = true,
                pendingQuestDates = setOf(LocalDate.of(2026, 7, 24)),
            ),
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
        // Fail once on the second backup write: the Room commit has landed but the created
        // ids have not been persisted yet. A naive retry diffs the already-refiled ledger,
        // finds no changes, overwrites this backup with an empty one, and marks applied.
        val failing = FailOnceOnSecondBackup(realSettings)
        val useCase = CloudPresencePlaytimeRefilingUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            cloudHistoricalDao = database.cloudHistoricalDao(),
            settings = failing,
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = RoomDatabaseTransactionScope(database),
        )
        val originalStart = utc("2026-07-25T23:50:00Z")
        val midnight = utc("2026-07-26T00:00:00Z")
        val originalEnd = utc("2026-07-26T00:10:00Z")

        try {
            realSettings.clearCloudPresenceRefiling()
            database.gameDao().upsert(
                Game(
                    appId = GAME,
                    name = "Portal",
                    iconUrl = "",
                    playtimeForever = 130,
                    playtime2Weeks = 0,
                    lastPlaytime = 130,
                    isGoal = true,
                    source = GameSource.STEAM_OWNED,
                ),
            )
            database.sessionDao().insert(
                Session(
                    appId = GAME,
                    startAt = originalStart,
                    endAt = originalEnd,
                    minutes = 30,
                    open = false,
                ),
            )
            database.playerProfileDao().upsert(PlayerProfile(longestStreak = 7))
            database.dailyProgressDao().upsert(
                DailyProgress("2026-07-24", minutesPlayed = 99, goalMinutesPlayed = 40, questMet = true),
            )
            database.dailyProgressDao().upsert(
                DailyProgress("2026-07-25", minutesPlayed = 30, goalMinutesPlayed = 30, questMet = true),
            )

            val beforeSessions = database.sessionDao().getAll()
            val beforeDays = database.dailyProgressDao().getAllOrdered()
            val beforeMinutes = beforeSessions.sumOf { it.minutes }
            val intervals = listOf(
                CloudPresenceInterval(
                    appId = GAME,
                    gameName = "Portal",
                    startAt = originalStart,
                    endAt = midnight,
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
                CloudPresenceInterval(
                    appId = GAME,
                    gameName = "Portal",
                    startAt = midnight,
                    endAt = originalEnd,
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            )

            try {
                useCase.apply(intervals)
                fail("Expected the injected post-commit failure")
            } catch (expected: RuntimeException) {
                assertEquals("injected post-commit failure", expected.message)
            }
            assertFalse(realSettings.cloudPresenceRefilingApplied.first())
            val interrupted = realSettings.cloudPresenceRefilingBackup()
            // The useful backup survives the interruption: it still names the original row
            // rather than having been overwritten with an empty diff of the refilled ledger.
            assertTrue(interrupted != null)
            assertEquals(1, interrupted!!.sessions.size)
            assertEquals(beforeSessions.single().id, interrupted.sessions.single().id)
            assertTrue(interrupted.createdSessionIds.isEmpty())
            // The Room commit did land before the failure, so the ledger already differs.
            assertNotEquals(beforeSessions, database.sessionDao().getAll())
            assertEquals(beforeMinutes, database.sessionDao().getAll().sumOf { it.minutes })

            val retried = useCase.apply(intervals)

            assertEquals(CloudPresenceRefilingOperation.APPLIED, retried.operation)
            assertEquals(1, retried.sessionsRefiled)
            assertEquals(setOf("2026-07-25", "2026-07-26"), retried.datesAffected)
            assertTrue(realSettings.cloudPresenceRefilingApplied.first())
            val completed = realSettings.cloudPresenceRefilingBackup()
            assertTrue(completed != null)
            assertEquals(1, completed!!.sessions.size)
            assertEquals(1, completed.createdSessionIds.size)
            assertEquals(beforeMinutes, database.sessionDao().getAll().sumOf { it.minutes })
            assertTrue(database.dailyProgressDao().getByDate("2026-07-26") != null)

            val reversed = useCase.reverse()

            assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
            assertEquals(beforeSessions, database.sessionDao().getAll())
            assertEquals(beforeDays, database.dailyProgressDao().getAllOrdered())
            assertFalse(realSettings.cloudPresenceRefilingApplied.first())
        } finally {
            realSettings.clearCloudPresenceRefiling()
            database.close()
        }
    }

    @Test
    fun reversePreservesPlayRecordedAfterApply() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = DataStoreSettingsRepository(
            settingsFixture.create(),
        )
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 1,
                initialized = true,
                pendingQuestDates = setOf(LocalDate.of(2026, 7, 24)),
            ),
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
        val useCase = CloudPresencePlaytimeRefilingUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            cloudHistoricalDao = database.cloudHistoricalDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = RoomDatabaseTransactionScope(database),
        )
        val originalStart = utc("2026-07-25T23:50:00Z")
        val originalEnd = utc("2026-07-26T00:10:00Z")
        val presenceStart = utc("2026-07-26T00:00:00Z")
        val presenceEnd = originalEnd

        try {
            settings.clearCloudPresenceRefiling()
            database.gameDao().upsert(
                Game(
                    appId = GAME,
                    name = "Portal",
                    iconUrl = "",
                    playtimeForever = 130,
                    playtime2Weeks = 0,
                    lastPlaytime = 130,
                    isGoal = true,
                    source = GameSource.STEAM_OWNED,
                ),
            )
            database.sessionDao().insert(
                Session(
                    appId = GAME,
                    startAt = originalStart,
                    endAt = originalEnd,
                    minutes = 30,
                    open = false,
                ),
            )
            database.playerProfileDao().upsert(PlayerProfile(longestStreak = 7))
            database.dailyProgressDao().upsert(
                DailyProgress("2026-07-24", minutesPlayed = 99, goalMinutesPlayed = 40, questMet = true),
            )
            database.dailyProgressDao().upsert(
                DailyProgress("2026-07-25", minutesPlayed = 30, goalMinutesPlayed = 30, questMet = true),
            )

            val intervals = listOf(
                CloudPresenceInterval(
                    appId = GAME,
                    gameName = "Portal",
                    startAt = presenceStart,
                    endAt = presenceEnd,
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            )

            val applied = useCase.apply(intervals)
            assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
            assertTrue(database.dailyProgressDao().getByDate("2026-07-26") != null)

            // Play recorded after the apply, on both an existing date and the date the
            // re-file created — written the way a sync writes it.
            val laterOnExistingStart = utc("2026-07-25T10:00:00Z")
            val laterOnCreatedStart = utc("2026-07-26T10:00:00Z")
            database.sessionDao().insert(
                Session(
                    appId = GAME,
                    startAt = laterOnExistingStart,
                    endAt = utc("2026-07-25T10:20:00Z"),
                    minutes = 20,
                    open = false,
                ),
            )
            database.dailyProgressDao().ensureDate("2026-07-25")
            database.dailyProgressDao().addMinutes("2026-07-25", 20, 20)
            database.sessionDao().insert(
                Session(
                    appId = GAME,
                    startAt = laterOnCreatedStart,
                    endAt = utc("2026-07-26T10:15:00Z"),
                    minutes = 15,
                    open = false,
                ),
            )
            database.dailyProgressDao().ensureDate("2026-07-26")
            database.dailyProgressDao().addMinutes("2026-07-26", 15, 15)

            val reversed = useCase.reverse()

            assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
            val sessions = database.sessionDao().getAll()
            assertEquals(30 + 20 + 15, sessions.sumOf { it.minutes })
            assertTrue(
                sessions.any { it.startAt == originalStart && it.endAt == originalEnd && it.minutes == 30 },
            )
            assertTrue(
                sessions.any { it.startAt == laterOnExistingStart && it.minutes == 20 },
            )
            assertTrue(
                sessions.any { it.startAt == laterOnCreatedStart && it.minutes == 15 },
            )
            // The existing date keeps the restored attribution plus the later play,
            // rather than being overwritten with the pre-apply snapshot.
            assertEquals(50, database.dailyProgressDao().getByDate("2026-07-25")!!.minutesPlayed)
            assertEquals(50, database.dailyProgressDao().getByDate("2026-07-25")!!.goalMinutesPlayed)
            // The created date keeps the later play instead of being deleted outright.
            val createdDay = database.dailyProgressDao().getByDate("2026-07-26")
            assertTrue(createdDay != null)
            assertEquals(15, createdDay!!.minutesPlayed)
            assertEquals(15, createdDay.goalMinutesPlayed)
            assertEquals(99, database.dailyProgressDao().getByDate("2026-07-24")!!.minutesPlayed)
        } finally {
            settings.clearCloudPresenceRefiling()
            database.close()
        }
    }

    private class FailOnceOnSecondBackup(
        private val delegate: SettingsRepository,
    ) : SettingsRepository by delegate {
        private var backupWrites = 0

        override suspend fun setCloudPresenceRefilingBackup(backup: CloudPresenceRefilingBackup) {
            backupWrites += 1
            if (backupWrites == 2) throw RuntimeException("injected post-commit failure")
            delegate.setCloudPresenceRefilingBackup(backup)
        }
    }

    private class FailOnceOnHistoricalBackup(
        private val delegate: SettingsRepository,
    ) : SettingsRepository by delegate {
        private var failed = false

        override suspend fun completeCloudPresenceRefiling(
            backup: CloudPresenceRefilingBackup,
            receipt: com.example.backlogium.data.repo.CloudPresenceRefilingReceipt,
        ) {
            if (!failed) {
                failed = true
                throw RuntimeException("injected historical marker failure")
            }
            delegate.completeCloudPresenceRefiling(backup, receipt)
        }
    }

    private class FailAroundHistoricalReverseCleanup(
        private val delegate: SettingsRepository,
    ) : SettingsRepository by delegate {
        private var clearCalls = 0

        override suspend fun clearCloudPresenceRefiling() {
            clearCalls += 1
            when (clearCalls) {
                1 -> throw RuntimeException("injected failure before historical cleanup")
                2 -> {
                    delegate.clearCloudPresenceRefiling()
                    throw RuntimeException("injected failure after historical cleanup")
                }
                else -> delegate.clearCloudPresenceRefiling()
            }
        }
    }

    private class FailOnHistoricalReverseRecompute(
        private val delegate: SettingsRepository,
    ) : SettingsRepository by delegate {
        override val ruleConfigWithVersion: Flow<VersionedRuleConfig> = flow {
            throw RuntimeException("injected failure during historical reverse recompute")
        }
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = utc("2026-07-26T12:00:00Z")
        override fun zone() = ZoneOffset.UTC
        override fun today() = LocalDate.of(2026, 7, 26)
    }

    private companion object {
        const val GAME = 440L

        fun utc(value: String): Long = Instant.parse(value).toEpochMilli()
    }
}
