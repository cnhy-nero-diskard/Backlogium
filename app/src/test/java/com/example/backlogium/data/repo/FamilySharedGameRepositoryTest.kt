package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeGameDao
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class FamilySharedGameRepositoryTest {

    @Test
    fun `track again restores the shared game row and removes its exclusion`() = runTest {
        val appId = 42L
        val gameDao = FakeGameDao(emptyList())
        val excludedDao = FakeExcludedSharedGameDao(
            ExcludedSharedGame(appId = appId, name = "Shared Game", excludedAt = 1_000L),
        )
        val repository = repository(gameDao, excludedDao)

        assertTrue(repository.reverseRemoval(appId))

        val restored = gameDao.getById(appId)
        assertNotNull(restored)
        assertEquals("Shared Game", restored?.name)
        assertEquals(GameSource.FAMILY_SHARED, restored?.source)
        assertEquals(2_000L, restored?.lastSyncedAt)
        assertEquals(listOf(appId), gameDao.observeLibrary().first().map { it.appId })
        assertFalse(excludedDao.isExcluded(appId))
        assertFalse(repository.reverseRemoval(appId))
    }

    private fun repository(
        gameDao: GameDao,
        excludedDao: ExcludedSharedGameDao,
    ) = FamilySharedGameRepository(
        gameDao = gameDao,
        excludedDao = excludedDao,
        profileDao = noOpProxy(PlayerProfileDao::class.java),
        settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
        store = SteamStoreAppDataSource(noOpProxy(SteamStoreApi::class.java)),
        genres = GameGenreRepository(
            cacheDao = noOpProxy(GameGenreCacheDao::class.java),
            store = SteamStoreGenreDataSource(noOpProxy(SteamStoreApi::class.java)),
            time = FixedTimeProvider,
        ),
        policy = com.example.backlogium.domain.SharedGameAdmissionPolicy(),
        notifier = object : SharedGameNotifier {
            override fun notifyAdmitted(appId: Long, name: String): Boolean = false
        },
        steamApi = noOpProxy(SteamApi::class.java),
        time = FixedTimeProvider,
        sessionDao = noOpProxy(SessionDao::class.java),
        dailyProgressDao = noOpProxy(DailyProgressDao::class.java),
        transaction = PassThroughTransactionScope,
        gamificationUpdater = GamificationUpdater(
            sessionDao = noOpProxy(SessionDao::class.java),
            dailyProgressDao = noOpProxy(DailyProgressDao::class.java),
            playerProfileDao = noOpProxy(PlayerProfileDao::class.java),
            hltbDataDao = noOpProxy(HltbDataDao::class.java),
            achievementDao = noOpProxy(AchievementDao::class.java),
            gameDao = noOpProxy(GameDao::class.java),
        ),
        derivedStateWrites = DerivedStateWriteCoordinator(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> noOpProxy(type: Class<T>): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, _, _ -> null } as T

    private object FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 2_000L
        override fun zone() = ZoneOffset.UTC
        override fun today() = LocalDate.of(2026, 8, 24)
    }

    private class FakeExcludedSharedGameDao(row: ExcludedSharedGame) : ExcludedSharedGameDao {
        private val rows = linkedMapOf(row.appId to row)

        override suspend fun upsert(row: ExcludedSharedGame) {
            rows[row.appId] = row
        }

        override suspend fun getAll(): List<ExcludedSharedGame> = rows.values.toList()

        override fun observeAll() = kotlinx.coroutines.flow.flowOf(rows.values.toList())

        override suspend fun isExcluded(appId: Long): Boolean = rows.containsKey(appId)

        override suspend fun delete(appId: Long) {
            rows.remove(appId)
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
