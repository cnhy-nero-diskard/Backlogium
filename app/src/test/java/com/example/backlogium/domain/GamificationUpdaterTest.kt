package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementFetchedAt
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameTrackedMinutes
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Integration test for [GamificationUpdater]: seeded sessions + daily progress feed the
 * `:gamification` engine, and the expected XP/level/quest/streak values are persisted.
 */
class GamificationUpdaterTest {

    @Test
    fun recompute_persistsExpectedXpLevelQuestAndStreak() = runTest {
        // 300 tracked minutes on one game with no HLTB row -> flat fallback -> 300 XP ->
        // exactly level 3. This guards the null-completionist path in the migrated engine.
        val sessionDao = FakeSessionDao(
            listOf(
                session(minutes = 200),
                session(minutes = 100),
            ),
        )
        val hltbDao = FakeHltbDataDao() // no rows -> completionistMinutes resolves to null
        // Four days: met, met, unmet, met -> current streak 1, longest 2.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-14", minutesPlayed = 45),
                DailyProgress("2026-07-15", minutesPlayed = 60),
                DailyProgress("2026-07-16", minutesPlayed = 10),
                DailyProgress("2026-07-17", minutesPlayed = 40),
            ),
        )
        val profileDao = FakePlayerProfileDao()
        val achievementDao = FakeAchievementDao(emptyList()) // empty set -> playtime-only totals

        val updater = GamificationUpdater(sessionDao, dailyDao, profileDao, hltbDao, achievementDao)
        updater.recompute(today = LocalDate.parse("2026-07-17"), config = RuleConfig())

        val profile = profileDao.get()!!
        assertEquals(300, profile.totalXp)
        assertEquals(3, profile.level)
        assertEquals(1, profile.currentStreak)
        assertEquals(2, profile.longestStreak)

        // Quest results persisted per day.
        assertTrue(dailyDao.getByDate("2026-07-14")!!.questMet)
        assertTrue(dailyDao.getByDate("2026-07-15")!!.questMet)
        assertEquals(false, dailyDao.getByDate("2026-07-16")!!.questMet)
        assertTrue(dailyDao.getByDate("2026-07-17")!!.questMet)
    }

    @Test
    fun recompute_addsAchievementXpOnTopOfPlaytimeXp() = runTest {
        // Same 300 playtime-XP setup as above, plus one unlocked, snapshotted (rare, 5% ->
        // 40 XP) achievement and one locked achievement (contributes nothing) -> 340 total.
        val sessionDao = FakeSessionDao(listOf(session(minutes = 200), session(minutes = 100)))
        val hltbDao = FakeHltbDataDao()
        val dailyDao = FakeDailyProgressDao(listOf(DailyProgress("2026-07-17", minutesPlayed = 40)))
        val profileDao = FakePlayerProfileDao()
        val achievementDao = FakeAchievementDao(
            listOf(
                Achievement(
                    appId = 1L,
                    apiName = "ACH_UNLOCKED",
                    unlocked = true,
                    snapshotPercent = 10.0,
                    fetchedAt = 0L,
                ),
                Achievement(
                    appId = 1L,
                    apiName = "ACH_LOCKED",
                    unlocked = false,
                    fetchedAt = 0L,
                ),
            ),
        )

        val updater = GamificationUpdater(sessionDao, dailyDao, profileDao, hltbDao, achievementDao)
        updater.recompute(today = LocalDate.parse("2026-07-17"), config = RuleConfig())

        assertEquals(340, profileDao.get()!!.totalXp)
    }

    private fun session(minutes: Int) = Session(
        appId = 1L,
        startAt = 0L,
        endAt = 0L,
        minutes = minutes,
        open = false,
    )

    // --- Fakes ---------------------------------------------------------------

    private class FakeSessionDao(private val sessions: List<Session>) : SessionDao {
        override suspend fun insert(session: Session): Long = 0L
        override suspend fun update(session: Session) = Unit
        override suspend fun getOpenSession(appId: Long): Session? = null
        override fun observeRecent(limit: Int): Flow<List<Session>> = flowOf(sessions)
        override suspend fun getAll(): List<Session> = sessions
        override suspend fun trackedMinutesByGame(): List<GameTrackedMinutes> =
            sessions.groupBy { it.appId }
                .map { (appId, group) -> GameTrackedMinutes(appId, group.sumOf { it.minutes }) }
    }

    /** No HLTB rows: every lookup returns null, exercising the engine's flat-rate fallback. */
    private class FakeHltbDataDao : HltbDataDao {
        override suspend fun upsert(data: HltbData) = Unit
        override suspend fun getByAppId(appId: Long): HltbData? = null
        override fun observeAll(): Flow<List<HltbData>> = flowOf(emptyList())
        override suspend fun getAll(): List<HltbData> = emptyList()
        override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(emptyList())
        override suspend fun appIdsStaleOrMissing(cutoff: Long): List<Long> = emptyList()
    }

    private class FakeDailyProgressDao(initial: List<DailyProgress>) : DailyProgressDao {
        private val store = linkedMapOf<String, DailyProgress>()

        init {
            initial.forEach { store[it.date] = it }
        }

        override suspend fun upsert(day: DailyProgress) {
            store[day.date] = day
        }

        override suspend fun getByDate(date: String): DailyProgress? = store[date]
        override fun observeAll(): Flow<List<DailyProgress>> =
            flowOf(store.values.sortedByDescending { it.date })

        override suspend fun getAllOrdered(): List<DailyProgress> =
            store.values.sortedBy { it.date }
    }

    private class FakePlayerProfileDao : PlayerProfileDao {
        private var profile: PlayerProfile? = null
        override suspend fun upsert(profile: PlayerProfile) {
            this.profile = profile
        }

        override fun observe(): Flow<PlayerProfile?> = flowOf(profile)
        override suspend fun get(): PlayerProfile? = profile
    }

    /** Seeded, read-only stand-in: only [getAllUnlocked] is exercised by the updater. */
    private class FakeAchievementDao(private val achievements: List<Achievement>) : AchievementDao {
        override suspend fun upsertAll(achievements: List<Achievement>) = Unit
        override fun observeForGame(appId: Long): Flow<List<Achievement>> = flowOf(emptyList())
        override suspend fun getForGame(appId: Long): List<Achievement> = emptyList()
        override fun observeCounts(): Flow<List<AchievementCounts>> = flowOf(emptyList())
        override suspend fun fetchedAtByApp(): List<AchievementFetchedAt> = emptyList()
        override suspend fun deleteMarker(appId: Long) = Unit
        override suspend fun getAllUnlocked(): List<Achievement> = achievements.filter { it.unlocked }
    }
}
