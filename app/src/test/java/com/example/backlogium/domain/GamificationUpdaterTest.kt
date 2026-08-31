package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.HltbDatasetLookup
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    fun persistQuestStatusCannotOverwriteMinutesCreditedAfterComputeSnapshot() = runTest {
        val snapshotTaken = CompletableDeferred<Unit>()
        val releaseCompute = CompletableDeferred<Unit>()
        val date = "2026-07-17"
        val dailyDao = FakeDailyProgressDao(
            initial = listOf(
                DailyProgress(
                    date,
                    minutesPlayed = 100,
                    goalMinutesPlayed = 40,
                    questMet = false,
                ),
            ),
            beforeGetAllOrdered = {
                snapshotTaken.complete(Unit)
                releaseCompute.await()
            },
        )
        val updater = GamificationUpdater(
            FakeSessionDao(emptyList()),
            dailyDao,
            FakePlayerProfileDao(),
            FakeHltbDataDao(),
            FakeAchievementDao(emptyList()),
            FakeGameDao(emptyList()),
        )

        val computed = async { updater.compute(LocalDate.parse(date), RuleConfig()) }
        snapshotTaken.await()
        dailyDao.addMinutes(date, minutesPlayed = 10, goalMinutesPlayed = 5)
        releaseCompute.complete(Unit)

        updater.persist(computed.await(), RecomputeSource.SYNC)

        assertEquals(110, dailyDao.getByDate(date)!!.minutesPlayed)
        assertEquals(45, dailyDao.getByDate(date)!!.goalMinutesPlayed)
        assertTrue(dailyDao.getByDate(date)!!.questMet)
    }

    @Test
    fun recompute_persistsExpectedXpLevelQuestAndStreak() = runTest {
        // 300 tracked minutes on one game with no HLTB row -> flat fallback -> 300 XP ->
        // exactly level 3. This guards the null-completionist path in the migrated engine.
        val sessionDao = FakeSessionDao(
            listOf(
                testSession(minutes = 200),
                testSession(minutes = 100),
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
        val gameDao = FakeGameDao(listOf(testGame(appId = 1L, backfillMinutes = 0)))

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
        val sessionDao = FakeSessionDao(listOf(testSession(minutes = 200), testSession(minutes = 100)))
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

        val gameDao = FakeGameDao(listOf(testGame(appId = 1L, backfillMinutes = 0)))

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
        val sessionDao = FakeSessionDao(listOf(testSession(minutes = 100)))
        val hltbDao = FakeHltbDataDao(completionistByAppId = mapOf(1L to 1000))
        val dailyDao = FakeDailyProgressDao(listOf(DailyProgress("2026-07-17", minutesPlayed = 40)))
        val profileDao = FakePlayerProfileDao()
        val achievementDao = FakeAchievementDao(emptyList())
        val gameDao = FakeGameDao(listOf(testGame(appId = 1L, backfillMinutes = 5000)))

        val updater =
            GamificationUpdater(sessionDao, dailyDao, profileDao, hltbDao, achievementDao, gameDao)
        updater.recompute(today = LocalDate.parse("2026-07-17"), config = RuleConfig())

        assertEquals(400, profileDao.get()!!.totalXp)
    }

    @Test
    fun recompute_usesDatasetOnlyCompletionistLengthForTaperAfterSync() = runTest {
        val profileDao = FakePlayerProfileDao()
        val datasetRow = HltbData(
            appId = 1L,
            hltbId = 10L,
            completionistMinutes = 1_000,
            fetchedAt = 1_000L,
            matchStatus = HltbMatchStatus.RESOLVED,
            origin = HltbDataOrigin.DATASET,
        )
        val datasetLookup = object : HltbDatasetLookup {
            override suspend fun find(appId: Long): HltbData? =
                datasetRow.takeIf { it.appId == appId }

            override suspend fun getAll(): List<HltbData> = listOf(datasetRow)
        }
        val updater = GamificationUpdater(
            sessionDao = FakeSessionDao(listOf(testSession(minutes = 100))),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = FakeGameDao(listOf(testGame(appId = 1L, backfillMinutes = 5_000))),
            hltbDatasetLookup = datasetLookup,
        )

        updater.recompute(
            today = LocalDate.parse("2026-07-17"),
            source = RecomputeSource.SYNC,
            config = RuleConfig(),
        )

        assertEquals(400, profileDao.get()!!.totalXp)
    }

    @Test
    fun compute_readsHltbFixtureWithOneBulkQuery() = runTest {
        val appIds = (1L..100L).toList()
        val hltbDao = FakeHltbDataDao(appIds.associateWith { 1_000 })
        val updater = GamificationUpdater(
            sessionDao = FakeSessionDao(appIds.map { testSession(minutes = 1, appId = it) }),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = FakePlayerProfileDao(),
            hltbDataDao = hltbDao,
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = FakeGameDao(appIds.map { testGame(appId = it, backfillMinutes = 0) }),
        )

        updater.compute(LocalDate.parse("2026-07-17"), RuleConfig())

        assertEquals("one library-sized HLTB read", 1, hltbDao.getAllCalls)
        assertEquals("the old per-game query is gone", 0, hltbDao.getByAppIdCalls)
    }

    @Test
    fun recompute_streakIntactTodayUnmet_currentStreakEqualsYesterdaysValue() = runTest {
        // Yesterday and the day before both met (past streak = 2); today's row exists but is
        // still in progress (10 < 30 min threshold). The engine must never see today's row as
        // a completed, unmet day, so the persisted streak stays at 2, not 0.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-14", minutesPlayed = 45),
                DailyProgress("2026-07-15", minutesPlayed = 60),
                DailyProgress("2026-07-16", minutesPlayed = 10),
            ),
        )
        val (updater, profileDao) = updaterWith(dailyDao)
        updater.recompute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())

        assertEquals(2, profileDao.get()!!.currentStreak)
    }

    @Test
    fun recompute_streakIntactTodayMet_currentStreakExtendsByOne() = runTest {
        // Same past streak of 2, but today's quest has now been met (40 >= 30) -> extends
        // immediately to 3, exactly as any other met day would.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-14", minutesPlayed = 45),
                DailyProgress("2026-07-15", minutesPlayed = 60),
                DailyProgress("2026-07-16", minutesPlayed = 40),
            ),
        )
        val (updater, profileDao) = updaterWith(dailyDao)
        updater.recompute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())

        assertEquals(3, profileDao.get()!!.currentStreak)
    }

    @Test
    fun recompute_densifiesCalendarGaps_beforeFoldingStreak() = runTest {
        // Monday, Thursday, and Friday are all met. The synthesized Tuesday and Wednesday break
        // the old order-only false streak, leaving only Thursday and Friday current.
        val days = listOf(
            DailyProgress("2026-07-13", minutesPlayed = 45),
            DailyProgress("2026-07-16", minutesPlayed = 45),
            DailyProgress("2026-07-17", minutesPlayed = 45),
        )
        val dailyDao = FakeDailyProgressDao(days)
        val (updater, profileDao) = updaterWith(dailyDao)

        updater.recompute(today = LocalDate.parse("2026-07-17"), config = RuleConfig())

        assertEquals(2, profileDao.get()!!.currentStreak)
        assertEquals(2, profileDao.get()!!.longestStreak)
        assertEquals(days.map { it.date }, dailyDao.getAllOrdered().map { it.date })
    }

    @Test
    fun recompute_graceCanForgiveOneSynthesizedGap() = runTest {
        // One missing day is eligible for the configured grace allowance, so Monday through
        // Thursday remains one continuous three-met-day streak with the forgiven gap.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-13", minutesPlayed = 45),
                DailyProgress("2026-07-15", minutesPlayed = 45),
                DailyProgress("2026-07-16", minutesPlayed = 45),
            ),
        )
        val (updater, profileDao) = updaterWith(dailyDao)

        updater.recompute(
            today = LocalDate.parse("2026-07-16"),
            config = RuleConfig(streakGraceDays = 1),
        )

        assertEquals(3, profileDao.get()!!.currentStreak)
        assertEquals(3, profileDao.get()!!.longestStreak)
        assertEquals(3, dailyDao.getAllOrdered().size)
    }

    @Test
    fun recompute_synthesizedDaysNeverCreateRowsOrChangedDayWrites() = runTest {
        val storedDays = listOf(
            DailyProgress("2026-07-10", minutesPlayed = 45, questMet = true),
            DailyProgress("2026-07-13", minutesPlayed = 45, questMet = true),
        )
        val dailyDao = FakeDailyProgressDao(storedDays)
        val (updater, profileDao) = updaterWith(dailyDao)

        updater.recompute(today = LocalDate.parse("2026-07-13"), config = RuleConfig())

        assertEquals(storedDays.map { it.date }, dailyDao.getAllOrdered().map { it.date })
        assertEquals(0, dailyDao.questUpdateCount)
        assertEquals(1, profileDao.get()!!.currentStreak)
    }

    @Test
    fun compute_densificationStartsAtEarliestStoredDate() = runTest {
        val dailyDao = FakeDailyProgressDao(
            listOf(DailyProgress("2026-07-10", minutesPlayed = 45)),
        )
        val (updater, _) = updaterWith(dailyDao)

        val result = updater.compute(LocalDate.parse("2026-07-13"), RuleConfig())

        assertEquals(
            listOf("2026-07-10", "2026-07-11", "2026-07-12", "2026-07-13"),
            result.questResults.map { it.date.toString() },
        )
        assertEquals(1, dailyDao.getAllOrdered().size)
    }

    @Test
    fun recompute_pastDayCreditReevaluatesAndPersistsQuestStatus() = runTest {
        val dailyDao = FakeDailyProgressDao(
            listOf(DailyProgress("2026-07-15", minutesPlayed = 45, questMet = false)),
        )
        val (updater, profileDao) = updaterWith(dailyDao)

        updater.recompute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())

        assertTrue(dailyDao.getByDate("2026-07-15")!!.questMet)
        assertEquals(1, dailyDao.questUpdateCount)
        assertEquals(1, profileDao.get()!!.currentStreak)
    }

    @Test
    fun recompute_streakAlreadyBrokenBeforeToday_currentStreakIsZero() = runTest {
        // Yesterday broke the streak (unmet, no grace configured) before today even started.
        // Whether today's row is unmet-so-far or hasn't been created yet, the persisted streak
        // is 0 either way -- there's nothing intact left for today to carry forward.
        val config = RuleConfig()
        val brokenPastDays = listOf(
            DailyProgress("2026-07-14", minutesPlayed = 45),
            DailyProgress("2026-07-15", minutesPlayed = 10), // unmet, breaks the streak
        )

        val todayUnmetDao = FakeDailyProgressDao(
            brokenPastDays + DailyProgress("2026-07-16", minutesPlayed = 5),
        )
        val (updaterUnmet, profileDaoUnmet) = updaterWith(todayUnmetDao)
        updaterUnmet.recompute(today = LocalDate.parse("2026-07-16"), config = config)
        assertEquals(0, profileDaoUnmet.get()!!.currentStreak)

        val todayMissingDao = FakeDailyProgressDao(brokenPastDays)
        val (updaterMissing, profileDaoMissing) = updaterWith(todayMissingDao)
        updaterMissing.recompute(today = LocalDate.parse("2026-07-16"), config = config)
        assertEquals(0, profileDaoMissing.get()!!.currentStreak)
    }

    @Test
    fun recompute_longestStreak_reflectsHistoricalMaxAndUpdatesWhenTodayExtendsPastIt() = runTest {
        // Historical longest run is 2 (07-10..07-11), broken on 07-12, then a fresh 2-day run
        // through yesterday (07-13..07-14). Today (07-15) meets its quest, extending the fresh
        // run to 3 -- past the old historical max of 2 -- so longest must update to 3.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-10", minutesPlayed = 45),
                DailyProgress("2026-07-11", minutesPlayed = 45),
                DailyProgress("2026-07-12", minutesPlayed = 5),
                DailyProgress("2026-07-13", minutesPlayed = 45),
                DailyProgress("2026-07-14", minutesPlayed = 45),
                DailyProgress("2026-07-15", minutesPlayed = 45),
            ),
        )
        val (updater, profileDao) = updaterWith(dailyDao)
        updater.recompute(today = LocalDate.parse("2026-07-15"), config = RuleConfig())

        val profile = profileDao.get()!!
        assertEquals(3, profile.currentStreak)
        assertEquals(3, profile.longestStreak)
    }

    @Test
    fun recompute_graceAllowance_stillAppliesAcrossPastDays() = runTest {
        // One grace day configured. Yesterday's miss (07-11) is forgiven -- the streak survives
        // without growing -- then 07-12 extends it back to 2. Today (07-13) is still in
        // progress (unmet so far), so the partition must not disturb the grace bookkeeping:
        // the persisted streak stays at 2, the value carried in from the completed days.
        val config = RuleConfig(streakGraceDays = 1)
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-10", minutesPlayed = 45),
                DailyProgress("2026-07-11", minutesPlayed = 5), // forgiven within grace
                DailyProgress("2026-07-12", minutesPlayed = 45),
                DailyProgress("2026-07-13", minutesPlayed = 5), // today, still in progress
            ),
        )
        val (updater, profileDao) = updaterWith(dailyDao)
        updater.recompute(today = LocalDate.parse("2026-07-13"), config = config)

        assertEquals(2, profileDao.get()!!.currentStreak)
    }

    @Test
    fun recompute_noRowYetForToday_treatedSameAsTodayUnmet() = runTest {
        // First sync of the day, before any `DailyProgress` row exists for today: the day list
        // has no entry at all for `today`, which must fold the same way an unmet-so-far row
        // would -- carrying the intact past streak forward, not zeroing it.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-14", minutesPlayed = 45),
                DailyProgress("2026-07-15", minutesPlayed = 60),
            ),
        )
        val (updater, profileDao) = updaterWith(dailyDao)
        updater.recompute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())

        assertEquals(2, profileDao.get()!!.currentStreak)
    }

    @Test
    fun compute_writesNothing() = runTest {
        // Days whose stored `questMet` all disagree with the config, and no profile row yet:
        // a recompute would write five times over. `compute()` must write none of them.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-14", minutesPlayed = 45, questMet = false),
                DailyProgress("2026-07-15", minutesPlayed = 60, questMet = false),
                DailyProgress("2026-07-16", minutesPlayed = 5, questMet = true),
            ),
        )
        val profileDao = FakePlayerProfileDao()
        val updater = GamificationUpdater(
            FakeSessionDao(listOf(testSession(minutes = 300))),
            dailyDao,
            profileDao,
            FakeHltbDataDao(),
            FakeAchievementDao(emptyList()),
            FakeGameDao(listOf(testGame(appId = 1L, backfillMinutes = 0))),
        )

        val result = updater.compute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())

        // The result carries the recomputed values...
        assertEquals(300, result.xpState.totalXp)
        assertEquals(3, result.xpState.level)
        assertEquals(2, result.currentStreak)
        // ...while every store is untouched.
        assertEquals(0, dailyDao.upsertCount)
        assertEquals(0, profileDao.upsertCount)
        assertEquals(null, profileDao.get())
        assertEquals(false, dailyDao.getByDate("2026-07-14")!!.questMet)
    }

    @Test
    fun persist_ofComputedResult_matchesRecompute() = runTest {
        // `recompute()` is defined as `persist(compute(...))`; spelling the two steps out by
        // hand must land the identical stored state.
        val days = listOf(
            DailyProgress("2026-07-14", minutesPlayed = 45),
            DailyProgress("2026-07-15", minutesPlayed = 60),
            DailyProgress("2026-07-16", minutesPlayed = 40),
        )
        val (viaRecompute, recomputeProfileDao) = updaterWith(FakeDailyProgressDao(days))
        viaRecompute.recompute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())

        val splitDailyDao = FakeDailyProgressDao(days)
        val (viaSplit, splitProfileDao) = updaterWith(splitDailyDao)
        viaSplit.persist(viaSplit.compute(LocalDate.parse("2026-07-16"), RuleConfig()))

        assertEquals(recomputeProfileDao.get(), splitProfileDao.get())
        assertTrue(splitDailyDao.getByDate("2026-07-16")!!.questMet)
    }

    @Test
    fun recompute_stricterQuestGoal_keepsLongestStreakButLowersCurrent() = runTest {
        // A 4-day run at 40 min/day earns a longest streak of 4 under the default 30-min goal.
        val days = listOf(
            DailyProgress("2026-07-13", minutesPlayed = 40),
            DailyProgress("2026-07-14", minutesPlayed = 40),
            DailyProgress("2026-07-15", minutesPlayed = 40),
            DailyProgress("2026-07-16", minutesPlayed = 40),
        )
        val dailyDao = FakeDailyProgressDao(days)
        val (updater, profileDao) = updaterWith(dailyDao)
        updater.recompute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())
        assertEquals(4, profileDao.get()!!.longestStreak)

        // Raising the goal to 60 min disqualifies every one of those days. The current streak
        // must follow the new rule down to 0; the record must survive it.
        updater.recompute(
            today = LocalDate.parse("2026-07-16"),
            config = RuleConfig(questThresholdMin = 60),
        )

        val profile = profileDao.get()!!
        assertEquals(0, profile.currentStreak)
        assertEquals(4, profile.longestStreak)
    }

    @Test
    fun recompute_longerStreakThanStored_stillRaisesLongest() = runTest {
        // The high-water floor is one-directional: a genuinely longer run still moves the record.
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-15", minutesPlayed = 40),
                DailyProgress("2026-07-16", minutesPlayed = 40),
            ),
        )
        val profileDao = FakePlayerProfileDao()
        profileDao.upsert(PlayerProfile(longestStreak = 1))
        val updater = GamificationUpdater(
            FakeSessionDao(emptyList()),
            dailyDao,
            profileDao,
            FakeHltbDataDao(),
            FakeAchievementDao(emptyList()),
            FakeGameDao(emptyList()),
        )
        updater.recompute(today = LocalDate.parse("2026-07-16"), config = RuleConfig())

        assertEquals(2, profileDao.get()!!.longestStreak)
    }

    /** Wires a [GamificationUpdater] with a fresh, empty profile/session/game/achievement setup
     * around the given daily-progress dao, so streak-focused tests only need to seed days. */
    private fun updaterWith(dailyDao: FakeDailyProgressDao): Pair<GamificationUpdater, FakePlayerProfileDao> {
        val profileDao = FakePlayerProfileDao()
        val updater = GamificationUpdater(
            FakeSessionDao(emptyList()),
            dailyDao,
            profileDao,
            FakeHltbDataDao(),
            FakeAchievementDao(emptyList()),
            FakeGameDao(emptyList()),
        )
        return updater to profileDao
    }
}
