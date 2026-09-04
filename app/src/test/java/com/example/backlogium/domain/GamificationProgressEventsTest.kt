package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.gamification.QuestResult
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.gamification.XpState
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamificationProgressEventsTest {
    private val today = LocalDate.parse("2026-08-13")

    @Test
    fun computeCandidateConfigDoesNotChangeProgressMarks() = runTest {
        val initialMarks = ProgressMarks(
            lastCelebratedLevel = 4,
            initialized = true,
        )
        val marksStore = InMemoryProgressMarksStore(initialMarks)
        val updater = updater(
            profile = PlayerProfile(level = 4),
            marksStore = marksStore,
            days = listOf(DailyProgress(today.toString(), minutesPlayed = 40)),
        )

        updater.compute(today, RuleConfig(xpPerMinute = 5))

        assertEquals(initialMarks, marksStore.read())
    }

    @Test
    fun syncLeavesRaisedLevelUnacknowledgedWhilePersistingProfile() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 4))
        val updater = updater(profileDao = profileDao, marksStore = marksStore)

        updater.persist(result(level = 7), RecomputeSource.SYNC)

        assertEquals(7, profileDao.get()!!.level)
        assertEquals(4, marksStore.read().lastCelebratedLevel)
    }

    @Test
    fun everyNonEarnedSourceReseedsRaisedLevel() = runTest {
        RecomputeSource.entries.filter { it != RecomputeSource.SYNC }.forEach { source ->
            val marksStore = InMemoryProgressMarksStore(
                ProgressMarks(lastCelebratedLevel = 4, initialized = true),
            )
            val updater = updater(profile = PlayerProfile(level = 4), marksStore = marksStore)

            updater.persist(result(level = 24), source)

            assertEquals("$source baseline", 24, marksStore.read().lastCelebratedLevel)
        }
    }

    @Test
    fun nonEarnedDropLowersBaselineForNextEarnedRise() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 24, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 24))
        val updater = updater(profileDao = profileDao, marksStore = marksStore)

        updater.persist(result(level = 4), RecomputeSource.BACKFILL)
        assertEquals(4, marksStore.read().lastCelebratedLevel)

        updater.persist(result(level = 5), RecomputeSource.SYNC)
        assertEquals(5, profileDao.get()!!.level)
        assertEquals(4, marksStore.read().lastCelebratedLevel)
    }

    @Test
    fun persistResolvesDanglingPendingTransitionBeforeStartingNewWork() = runTest {
        // Simulates a prior RULE_CHANGE crashing between its write-ahead record and its marks
        // finalize: Room already committed the jump from 4 to 24, but the marks still claim 4.
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 4,
                initialized = true,
                pendingTransition = PendingTransition(
                    source = RecomputeSource.RULE_CHANGE,
                    previousLevel = 4,
                    previousStreak = 0,
                    previousTodayQuestMet = false,
                    evaluationDate = today,
                ),
            ),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 24))
        val updater = updater(profileDao = profileDao, marksStore = marksStore)

        updater.persist(result(level = 30), RecomputeSource.SYNC)

        assertEquals(30, profileDao.get()!!.level)
        // The dangling transition reseeds to 24 (the RULE_CHANGE's actual, non-earned effect)
        // before this SYNC's own earned rise from 24 to 30 is measured against it — never a
        // phantom rise measured from the stale mark of 4.
        assertEquals(24, marksStore.read().lastCelebratedLevel)
        assertEquals(null, marksStore.read().pendingTransition)
    }

    // --- auditfix-session-ledger-integrity #114: a wrapped-total correction reseeds, never emits ---

    @Test
    fun pendingXpIntegrityCorrectionReseedsDespiteCallerDeclaringSync() = runTest {
        // The caller has no way to know this device was ever affected by the overflow bug — it
        // declares an ordinary SYNC, exactly like every other periodic poll. The profile's own
        // pendingXpIntegrityCorrection flag must override that, or a large upward correction from
        // a stored totalXp = 0 would fire a cascade of level-up celebrations for progress earned
        // long ago — the exact failure #104 is about, committed here instead while fixing #114.
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(
            PlayerProfile(level = 1, totalXp = 0L, pendingXpIntegrityCorrection = true),
        )
        val updater = updater(profileDao = profileDao, marksStore = marksStore)

        updater.persist(result(level = 47), RecomputeSource.SYNC)

        assertEquals(47, profileDao.get()!!.level)
        // Reseeded, not left at 1 to be "caught up to" by a fabricated cascade of LevelUp events.
        assertEquals(47, marksStore.read().lastCelebratedLevel)
    }

    @Test
    fun pendingXpIntegrityCorrectionIsClearedByTheCorrectiveRecompute() = runTest {
        val profileDao = FakePlayerProfileDao(
            PlayerProfile(level = 1, totalXp = 0L, pendingXpIntegrityCorrection = true),
        )
        val updater = updater(
            profileDao = profileDao,
            marksStore = InMemoryProgressMarksStore(ProgressMarks(initialized = true)),
        )

        updater.persist(result(level = 47), RecomputeSource.SYNC)

        assertEquals(false, profileDao.get()!!.pendingXpIntegrityCorrection)
    }

    @Test
    fun anOrdinaryFirstPlayAfterACorrectionIsEarnedNormally() = runTest {
        // Once the flag is cleared, the very next recompute is ordinary SYNC again — a real
        // level-up earned by play is not permanently suppressed by having once been corrected.
        // Earned rises are left unacknowledged for the celebration UI, exactly like
        // syncLeavesRaisedLevelUnacknowledgedWhilePersistingProfile — that non-bump, rather than
        // an immediate reseed to 48, is the proof this recompute went through the earned path.
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(
            PlayerProfile(level = 1, totalXp = 0L, pendingXpIntegrityCorrection = true),
        )
        val updater = updater(profileDao = profileDao, marksStore = marksStore)
        updater.persist(result(level = 47), RecomputeSource.SYNC) // the correction itself
        assertEquals(47, marksStore.read().lastCelebratedLevel) // reseeded, not left at 1

        updater.persist(result(level = 48), RecomputeSource.SYNC) // genuinely earned next

        assertEquals(48, profileDao.get()!!.level)
        assertEquals(47, marksStore.read().lastCelebratedLevel)
    }

    @Test
    fun firstPersistSeedsEvenForSync() = runTest {
        val marksStore = InMemoryProgressMarksStore()
        val updater = updater(profileDao = FakePlayerProfileDao(), marksStore = marksStore)

        updater.persist(result(level = 30, streak = 14, questMet = true), RecomputeSource.SYNC)

        val marks = marksStore.read()
        assertTrue(marks.initialized)
        assertEquals(30, marks.lastCelebratedLevel)
        assertEquals(14, marks.lastCelebratedStreakMilestone)
        assertEquals(today, marks.lastQuestCelebratedDate)
    }

    private fun updater(
        profile: PlayerProfile? = null,
        profileDao: FakePlayerProfileDao = FakePlayerProfileDao(profile),
        marksStore: InMemoryProgressMarksStore,
        days: List<DailyProgress> = emptyList(),
    ): GamificationUpdater = GamificationUpdater(
        sessionDao = FakeSessionDao(emptyList()),
        dailyProgressDao = FakeDailyProgressDao(days),
        playerProfileDao = profileDao,
        hltbDataDao = FakeHltbDataDao(),
        achievementDao = FakeAchievementDao(emptyList()),
        gameDao = FakeGameDao(emptyList()),
        progressMarksStore = marksStore,
    )

    private fun result(
        level: Int,
        streak: Int = 0,
        questMet: Boolean = false,
    ): GamificationResult = GamificationResult(
        xpState = XpState(totalXp = 0, level = level, xpIntoLevel = 0, xpForNext = 100),
        questResults = listOf(QuestResult(today, questMet)),
        currentStreak = streak,
        longestStreak = streak,
        changedDays = emptyList(),
        evaluationDate = today,
    )
}
