package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
