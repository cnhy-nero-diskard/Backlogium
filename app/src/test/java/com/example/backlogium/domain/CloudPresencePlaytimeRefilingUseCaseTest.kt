package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CloudPresencePlaytimeRefilingUseCaseTest {

    @Test
    fun applyAndReverseRestoresTheLedgerExactlyAndCanBeOfferedAgain() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = DataStoreSettingsRepository(
            SettingsDataStore(RuntimeEnvironment.getApplication()),
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

            val applied = useCase.apply(intervals)

            assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
            assertEquals(1, applied.sessionsRefiled)
            assertEquals(setOf("2026-07-25", "2026-07-26"), applied.datesAffected)
            assertEquals(beforeMinutes, database.sessionDao().getAll().sumOf { it.minutes })
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

            val repeated = useCase.apply(intervals)
            assertEquals(CloudPresenceRefilingOperation.NO_OP, repeated.operation)

            val reversed = useCase.reverse()

            assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
            assertEquals(beforeSessions, database.sessionDao().getAll())
            assertEquals(beforeDays, database.dailyProgressDao().getAllOrdered())
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
            SettingsDataStore(RuntimeEnvironment.getApplication()),
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
            SettingsDataStore(RuntimeEnvironment.getApplication()),
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
