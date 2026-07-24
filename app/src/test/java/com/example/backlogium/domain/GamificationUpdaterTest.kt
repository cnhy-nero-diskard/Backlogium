package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementFetchedAt
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameTrackedMinutes
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
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
        // Zero-backfill game: regression guard that pre-import installs compute exactly as
        // before (backfillMinutes = 0 adds nothing to the tracked total).
        val gameDao = FakeGameDao(listOf(game(appId = 1L, backfillMinutes = 0)))

        val updater =
            GamificationUpdater(sessionDao, dailyDao, profileDao, hltbDao, achievementDao, gameDao)
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

        val gameDao = FakeGameDao(listOf(game(appId = 1L, backfillMinutes = 0)))

        val updater =
            GamificationUpdater(sessionDao, dailyDao, profileDao, hltbDao, achievementDao, gameDao)
        updater.recompute(today = LocalDate.parse("2026-07-17"), config = RuleConfig())

        assertEquals(340, profileDao.get()!!.totalXp)
    }

    @Test
    fun recompute_combinesBackfillWithTrackedMinutesAndCapsViaTaper() = runTest {
        // A HLTB-matched game (completionist average 1000 min -> zero point Z = 2000) with a
        // large frozen backfill offset. Combined total = 5000 backfill + 100 tracked = 5100,
        // far beyond Z, so the taper caps its XP at Z/(k+1) = 2000/5 = 400 regardless of the
        // raw historical hours. Tracked-only (100 min) would yield just 90 XP, so the 400
        // proves the frozen offset is folded into one cumulative, tapered total.
        val sessionDao = FakeSessionDao(listOf(session(minutes = 100)))
        val hltbDao = FakeHltbDataDao(completionistByAppId = mapOf(1L to 1000))
        val dailyDao = FakeDailyProgressDao(listOf(DailyProgress("2026-07-17", minutesPlayed = 40)))
        val profileDao = FakePlayerProfileDao()
        val achievementDao = FakeAchievementDao(emptyList())
        val gameDao = FakeGameDao(listOf(game(appId = 1L, backfillMinutes = 5000)))

        val updater =
            GamificationUpdater(sessionDao, dailyDao, profileDao, hltbDao, achievementDao, gameDao)
        updater.recompute(today = LocalDate.parse("2026-07-17"), config = RuleConfig())

        assertEquals(400, profileDao.get()!!.totalXp)
    }

    private fun session(minutes: Int) = Session(
        appId = 1L,
        startAt = 0L,
        endAt = 0L,
        minutes = minutes,
        open = false,
    )

    private fun game(appId: Long, backfillMinutes: Int) = Game(
        appId = appId,
        name = "Game $appId",
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
        backfillMinutes = backfillMinutes,
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

    /**
     * HLTB stand-in. With no configured rows every lookup returns null, exercising the
     * engine's flat-rate fallback; [completionistByAppId] supplies a resolved completionist
     * length for specific games so the diminishing-returns taper can be exercised.
     */
    private class FakeHltbDataDao(
        private val completionistByAppId: Map<Long, Int> = emptyMap(),
    ) : HltbDataDao {
        override suspend fun upsert(data: HltbData) = Unit
        override suspend fun getByAppId(appId: Long): HltbData? =
            completionistByAppId[appId]?.let { minutes ->
                HltbData(
                    appId = appId,
                    completionistMinutes = minutes,
                    fetchedAt = 0L,
                    matchStatus = HltbMatchStatus.RESOLVED,
                )
            }

        override fun observeAll(): Flow<List<HltbData>> = flowOf(emptyList())
        override suspend fun getAll(): List<HltbData> = emptyList()
        override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(emptyList())
        override suspend fun appIdsStaleOrMissing(cutoff: Long): List<Long> = emptyList()
    }

    /** Seeded game store; only [getAll] is exercised by the updater. */
    private class FakeGameDao(games: List<Game>) : GameDao {
        private val store = games.associateBy { it.appId }.toMutableMap()

        override suspend fun upsertAll(games: List<Game>) {
            games.forEach { store[it.appId] = it }
        }

        override suspend fun upsert(game: Game) {
            store[game.appId] = game
        }

        override fun observeLibrary(): Flow<List<Game>> = flowOf(store.values.toList())
        override fun observeGoalGames(): Flow<List<Game>> = flowOf(emptyList())
        override fun observeBacklog(): Flow<List<Game>> = flowOf(emptyList())
        override suspend fun goalAppIds(): List<Long> = emptyList()
        override suspend fun getAll(): List<Game> = store.values.toList()
        override suspend fun getById(appId: Long): Game? = store[appId]
        override suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?) = Unit
        override suspend fun setGoalFlag(appId: Long, isGoal: Boolean) = Unit
        override suspend fun count(): Int = store.size
        override suspend fun setBackfillMinutes(appId: Long, minutes: Int) {
            store[appId]?.let { store[appId] = it.copy(backfillMinutes = minutes) }
        }
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
