package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * [SetSharedGamePlaytimeUseCase] is the write path behind "Set hours played"
 * (add-shared-game-playtime-and-filter): it must apply only to family-shared games, reject a
 * negative value, and recompute XP so the change is reflected immediately.
 */
@RunWith(RobolectricTestRunner::class)
class SetSharedGamePlaytimeUseCaseTest {

    @Test
    fun setsTheEstimateAndRecomputesXpForAFamilySharedGame() = runTest {
        val gameDao = FakeGameDao(listOf(sharedGame(appId = 1L)))
        val profileDao = FakePlayerProfileDao()
        val updater = GamificationUpdater(
            sessionDao = FakeSessionDao(emptyList()),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(completionistByAppId = mapOf(1L to 1000)),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = gameDao,
        )
        val useCase = SetSharedGamePlaytimeUseCase(
            gameDao = gameDao,
            settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
            gamificationUpdater = updater,
            time = FixedTimeProvider,
            derivedStateWrites = DerivedStateWriteCoordinator(),
        )

        val applied = useCase(appId = 1L, minutes = 120)

        assertTrue(applied)
        assertEquals(120, gameDao.getById(1L)?.manualSharedMinutes)
        assertTrue("XP must be recomputed immediately, not left for the next sync", profileDao.get()!!.totalXp > 0)
    }

    @Test
    fun clearingWithZeroIsAccepted() = runTest {
        val gameDao = FakeGameDao(listOf(sharedGame(appId = 1L, manualSharedMinutes = 90)))
        val useCase = useCase(gameDao)

        val applied = useCase(appId = 1L, minutes = 0)

        assertTrue(applied)
        assertEquals(0, gameDao.getById(1L)?.manualSharedMinutes)
    }

    @Test
    fun rejectsAnOwnedGameWithoutWriting() = runTest {
        val gameDao = FakeGameDao(listOf(ownedGame(appId = 1L)))
        val useCase = useCase(gameDao)

        val applied = useCase(appId = 1L, minutes = 120)

        assertFalse(applied)
        assertEquals(0, gameDao.getById(1L)?.manualSharedMinutes)
    }

    @Test
    fun rejectsANegativeValueWithoutWriting() = runTest {
        val gameDao = FakeGameDao(listOf(sharedGame(appId = 1L, manualSharedMinutes = 45)))
        val useCase = useCase(gameDao)

        val applied = useCase(appId = 1L, minutes = -1)

        assertFalse(applied)
        assertEquals(45, gameDao.getById(1L)?.manualSharedMinutes)
    }

    @Test
    fun rejectsAnUnknownGameWithoutThrowing() = runTest {
        val useCase = useCase(FakeGameDao(emptyList()))

        assertFalse(useCase(appId = 404L, minutes = 60))
    }

    @Test
    fun rejectsAnOversizedEstimateWithoutWriting() = runTest {
        val gameDao = FakeGameDao(listOf(sharedGame(appId = 1L)))
        val useCase = useCase(gameDao)

        // A near-Int.MAX estimate plus any tracked minutes would overflow the Int sums
        // downstream, so the write path caps at MAX_MANUAL_SHARED_MINUTES instead.
        val applied = useCase(appId = 1L, minutes = SetSharedGamePlaytimeUseCase.MAX_MANUAL_SHARED_MINUTES + 1)

        assertFalse(applied)
        assertEquals(0, gameDao.getById(1L)?.manualSharedMinutes)
    }

    @Test
    fun acceptsAnEstimateAtTheCap() = runTest {
        val gameDao = FakeGameDao(listOf(sharedGame(appId = 1L)))
        val useCase = useCase(gameDao)

        val applied = useCase(appId = 1L, minutes = SetSharedGamePlaytimeUseCase.MAX_MANUAL_SHARED_MINUTES)

        assertTrue(applied)
        assertEquals(SetSharedGamePlaytimeUseCase.MAX_MANUAL_SHARED_MINUTES, gameDao.getById(1L)?.manualSharedMinutes)
    }

    private fun useCase(gameDao: FakeGameDao) = SetSharedGamePlaytimeUseCase(
        gameDao = gameDao,
        settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
        gamificationUpdater = GamificationUpdater(
            sessionDao = FakeSessionDao(emptyList()),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = FakePlayerProfileDao(),
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = gameDao,
        ),
        time = FixedTimeProvider,
        derivedStateWrites = DerivedStateWriteCoordinator(),
    )

    private fun sharedGame(appId: Long, manualSharedMinutes: Int = 0) = Game(
        appId = appId, name = "Shared Game", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0, source = GameSource.FAMILY_SHARED,
        manualSharedMinutes = manualSharedMinutes,
    )

    private fun ownedGame(appId: Long) = Game(
        appId = appId, name = "Owned Game", iconUrl = "", playtimeForever = 500,
        playtime2Weeks = 0, lastPlaytime = 500, source = GameSource.STEAM_OWNED,
    )

    private object FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 10_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.of(2026, 8, 24)
    }
}
