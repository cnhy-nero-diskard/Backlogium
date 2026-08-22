package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What hiding does — and deliberately does not do — to derived values (add-hidden-games).
 *
 * XP and level are an all-time aggregate over sessions, so they are recomputed with the hidden
 * game's minutes and achievements excluded. Daily quest results and streaks are dated facts about
 * days that happened, and are left exactly as recorded: a day the player met their quest remains a
 * day they met their quest, whatever bookkeeping preference is expressed months later.
 */
class HiddenGameDerivedValuesTest {

    private val today = LocalDate.parse("2026-07-17")

    /** 300 minutes on the kept game, 400 on the one being hidden; flat XP (no HLTB rows). */
    private fun fixture(hidden: Set<Long> = emptySet()): Fixture {
        val hiddenDao = FakeHiddenGameDao(hidden)
        val profileDao = FakePlayerProfileDao()
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress("2026-07-15", minutesPlayed = 45, questMet = true),
                DailyProgress("2026-07-16", minutesPlayed = 60, questMet = true),
                DailyProgress("2026-07-17", minutesPlayed = 40, questMet = true),
            ),
        )
        val updater = GamificationUpdater(
            FakeSessionDao(
                listOf(
                    testSession(minutes = 300, appId = KEPT),
                    testSession(minutes = 400, appId = TOOL),
                ),
            ),
            dailyDao,
            profileDao,
            FakeHltbDataDao(),
            FakeAchievementDao(
                listOf(
                    Achievement(
                        appId = TOOL,
                        apiName = "TOOL_ACH",
                        unlocked = true,
                        snapshotPercent = 10.0,
                        fetchedAt = 0L,
                    ),
                ),
            ),
            FakeGameDao(
                listOf(
                    testGame(appId = KEPT, backfillMinutes = 0),
                    testGame(appId = TOOL, backfillMinutes = 0),
                ),
            ),
            hiddenDao,
        )
        return Fixture(updater, hiddenDao, profileDao, dailyDao)
    }

    private class Fixture(
        val updater: GamificationUpdater,
        val hiddenDao: FakeHiddenGameDao,
        val profileDao: FakePlayerProfileDao,
        val dailyDao: FakeDailyProgressDao,
    )

    @Test
    fun compute_excludesAHiddenGamesMinutesAndAchievements() = runTest {
        val fixture = fixture()

        val everythingVisible = fixture.updater.compute(today, RuleConfig())
        // 700 flat playtime XP + 40 for the rare achievement on the tool. Level 4 (xpAt(4) = 600).
        assertEquals(740, everythingVisible.xpState.totalXp)
        assertEquals(4, everythingVisible.xpState.level)

        fixture.hiddenDao.upsertAll(listOf(HiddenGame(appId = TOOL, hiddenAt = 0L)))
        val withToolHidden = fixture.updater.compute(today, RuleConfig())

        // Exactly the kept game's 300 minutes: level 3, one level below what the tool bought.
        assertEquals(300, withToolHidden.xpState.totalXp)
        assertEquals(3, withToolHidden.xpState.level)
    }

    /**
     * The preview is the applied computation, not an estimate of it: computing against a candidate
     * hidden set has to produce exactly what hiding then produces, or the confirmation would be
     * able to state a number the hide does not deliver.
     */
    @Test
    fun computeWithACandidateHiddenSet_matchesWhatApplyingTheHideProduces() = runTest {
        val fixture = fixture()

        val preview = fixture.updater.compute(today, RuleConfig(), hiddenAppIds = setOf(TOOL))

        fixture.hiddenDao.upsertAll(listOf(HiddenGame(appId = TOOL, hiddenAt = 0L)))
        fixture.updater.recompute(today, RecomputeSource.VISIBILITY_CHANGE, RuleConfig())

        val profile = fixture.profileDao.get()!!
        assertEquals(preview.xpState.totalXp, profile.totalXp)
        assertEquals(preview.xpState.level, profile.level)
    }

    @Test
    fun unhiding_restoresExactlyTheXpAndLevelThatWouldHaveApplied() = runTest {
        val fixture = fixture()
        fixture.updater.recompute(today, RecomputeSource.SYNC, RuleConfig())
        val beforeHiding = fixture.profileDao.get()!!

        fixture.hiddenDao.upsertAll(listOf(HiddenGame(appId = TOOL, hiddenAt = 0L)))
        fixture.updater.recompute(today, RecomputeSource.VISIBILITY_CHANGE, RuleConfig())
        val whileHidden = fixture.profileDao.get()!!
        assertTrue("hiding is expected to lower XP here", whileHidden.totalXp < beforeHiding.totalXp)

        fixture.hiddenDao.delete(listOf(TOOL))
        fixture.updater.recompute(today, RecomputeSource.VISIBILITY_CHANGE, RuleConfig())

        val afterUnhiding = fixture.profileDao.get()!!
        assertEquals(beforeHiding.totalXp, afterUnhiding.totalXp)
        assertEquals(beforeHiding.level, afterUnhiding.level)
    }

    @Test
    fun hiding_leavesAPastMetDayMet() = runTest {
        val fixture = fixture()

        fixture.hiddenDao.upsertAll(listOf(HiddenGame(appId = TOOL, hiddenAt = 0L)))
        val result = fixture.updater.compute(today, RuleConfig())
        fixture.updater.recompute(today, RecomputeSource.VISIBILITY_CHANGE, RuleConfig())

        assertEquals("no stored day's quest status may change", emptyList<QuestStatusUpdate>(), result.changedDays)
        assertEquals(0, fixture.dailyDao.questUpdateCount)
        assertTrue(fixture.dailyDao.getByDate("2026-07-15")!!.questMet)
        assertTrue(fixture.dailyDao.getByDate("2026-07-16")!!.questMet)
        // The streak those days produced is untouched too, hiding being no evidence about a day.
        assertEquals(3, fixture.profileDao.get()!!.currentStreak)
        assertEquals(3, fixture.profileDao.get()!!.longestStreak)
    }

    @Test
    fun hiding_neverLowersTheLongestStreakRecord() = runTest {
        val fixture = fixture()
        fixture.updater.recompute(today, RecomputeSource.SYNC, RuleConfig())
        val record = fixture.profileDao.get()!!.longestStreak

        fixture.hiddenDao.upsertAll(listOf(HiddenGame(appId = KEPT, hiddenAt = 0L), HiddenGame(appId = TOOL, hiddenAt = 0L)))
        fixture.updater.recompute(today, RecomputeSource.VISIBILITY_CHANGE, RuleConfig())

        assertEquals(record, fixture.profileDao.get()!!.longestStreak)
    }

    private companion object {
        const val KEPT = 1L
        const val TOOL = 2L
    }
}
