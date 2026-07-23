package com.example.backlogium.gamification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Exhaustive JVM unit tests for the pure gamification engine. No instrumentation, no clock,
 * no I/O — every case is a pure function of its arguments. Covers the locked math boundaries
 * from the `add-gamification-engine` design.
 */
class GamificationTest {

    private val cfg = RuleConfig() // documented defaults

    // --- 4.1 Playtime XP (per game) -----------------------------------------

    @Test
    fun gameXp_isZeroAtZeroMinutes() {
        assertEquals(0, Gamification.gameXp(0, completionistAverageMinutes = 100, cfg))
    }

    @Test
    fun gameXp_earlyPlaytimeEarnsNearTheFlatRate() {
        // Small M relative to T: taper barely reduces the flat `minutes * rate` amount.
        val flat = Gamification.gameXp(10, completionistAverageMinutes = null, cfg) // 10
        val tapered = Gamification.gameXp(10, completionistAverageMinutes = 1000, cfg)
        assertTrue("taper only reduces XP", tapered <= flat)
        assertTrue("within a small reduction of flat", tapered >= 9)
    }

    @Test
    fun gameXp_marginalRateAtCompletionistAverageIsVerySmall() {
        // Marginal XP over a 100-min window early in the game vs. the same window at M = T.
        val t = 1000
        val early = Gamification.gameXp(100, t, cfg) - Gamification.gameXp(0, t, cfg)
        val atAverage = Gamification.gameXp(t + 100, t, cfg) - Gamification.gameXp(t, t, cfg)
        // The documented taper (0.5^4 = 6.25% base rate at M=T) makes the late window earn far less.
        assertTrue("early window should earn far more than the window at M=T ($early vs $atAverage)",
            early >= atAverage * 5)
        assertTrue("marginal rate at M=T is a small trickle", atAverage in 1..10)
    }

    @Test
    fun gameXp_earnsNothingAtOrBeyondTwiceTheAverage() {
        val t = 100 // Z = 2T = 200; theoretical cap = 0.4 * T = 40
        val atZeroPoint = Gamification.gameXp(200, t, cfg)
        assertEquals(40, atZeroPoint)
        // At and beyond Z the cumulative XP does not increase further.
        assertEquals(atZeroPoint, Gamification.gameXp(400, t, cfg))
        assertEquals(atZeroPoint, Gamification.gameXp(10_000, t, cfg))
    }

    @Test
    fun gameXp_atCompletionistAverageMatchesClosedForm() {
        // gameXp(T, T) = 0.4*T * (1 - 0.5^5) = 0.4*100 * 0.96875 = 38.75 -> 39
        assertEquals(39, Gamification.gameXp(100, 100, cfg))
    }

    @Test
    fun gameXp_isMonotonicNonDecreasing() {
        val t = 100
        var previous = -1
        for (m in 0..300 step 10) {
            val value = Gamification.gameXp(m, t, cfg)
            assertTrue("cumulative XP must not decrease at m=$m ($value < $previous)", value >= previous)
            previous = value
        }
    }

    @Test
    fun gameXp_fallsBackToFlatUncappedRateWhenNoAverage() {
        assertEquals(300, Gamification.gameXp(300, completionistAverageMinutes = null, cfg))
        // Uncapped: even huge playtime keeps earning at the flat rate.
        assertEquals(100_000, Gamification.gameXp(100_000, completionistAverageMinutes = null, cfg))
    }

    // --- 4.2 XP and levels ---------------------------------------------------

    @Test
    fun xp_exactThresholdsMapToLevels() {
        // Flat (null-completionist) games make the totals exact and easy to reason about.
        assertEquals(2, Gamification.xp(listOf(flatGame(100))).level) // 100 -> L2
        assertEquals(3, Gamification.xp(listOf(flatGame(300))).level) // 300 -> L3
    }

    @Test
    fun xp_justBelowThresholdStaysAtLowerLevel() {
        assertEquals(1, Gamification.xp(listOf(flatGame(99))).level) // 99 -> still L1
    }

    @Test
    fun xp_levelBoundaryReportsZeroProgressIntoLevel() {
        val state = Gamification.xp(listOf(flatGame(300)))
        assertEquals(300, state.totalXp)
        assertEquals(3, state.level)
        assertEquals(0, state.xpIntoLevel)
        assertEquals(300, state.xpForNext) // xpAt(4) - xpAt(3) = 600 - 300
    }

    @Test
    fun xp_withinLevelProgressFraction() {
        val state = Gamification.xp(listOf(flatGame(150)))
        assertEquals(2, state.level)
        assertEquals(50, state.xpIntoLevel) // 150 - xpAt(2)=100
        assertEquals(200, state.xpForNext) // xpAt(3)-xpAt(2)=300-100
    }

    @Test
    fun xp_zeroXpForEmptyLibrary() {
        val state = Gamification.xp(emptyList())
        assertEquals(0, state.totalXp)
        assertEquals(1, state.level)
        assertEquals(0, state.xpIntoLevel)
        assertEquals(100, state.xpForNext) // xpAt(2)-xpAt(1)=100-0
    }

    @Test
    fun xp_totalSummedAcrossMixedTaperedAndUntaperedGames() {
        // Flat game: 300 min, no average -> 300 XP. Tapered game: 200 min at T=100 -> capped 40 XP.
        val games = listOf(
            flatGame(300),
            GamePlaytimeInput(gameId = "b", minutesPlayed = 200, completionistAverageMinutes = 100),
        )
        val state = Gamification.xp(games)
        assertEquals(340, state.totalXp) // 300 + 40
        assertEquals(3, state.level)
        assertEquals(40, state.xpIntoLevel)
        assertEquals(300, state.xpForNext)
    }

    // --- 4.3 Goal progress ---------------------------------------------------

    @Test
    fun goalProgress_partialExactOverAndZeroTarget() {
        assertEquals(0.5, Gamification.goalProgress(30, 60).fraction, EPS) // partial
        assertEquals(1.0, Gamification.goalProgress(60, 60).fraction, EPS) // exact -> 1.0
        assertEquals(1.0, Gamification.goalProgress(120, 60).fraction, EPS) // over -> clamped
        assertEquals(0.0, Gamification.goalProgress(30, 0).fraction, EPS) // zero target guard
    }

    // --- 4.4 Daily quest -----------------------------------------------------

    @Test
    fun quest_metAtThresholdUnmetBelow() {
        assertTrue(Gamification.quest(DayInput(DAY, anyMinutes = 30, goalMinutes = 0), cfg).met)
        assertFalse(Gamification.quest(DayInput(DAY, anyMinutes = 29, goalMinutes = 0), cfg).met)
    }

    @Test
    fun quest_anyVsGoalOnlyMode() {
        val day = DayInput(DAY, anyMinutes = 100, goalMinutes = 10)
        // ANY (default): 100 min of any game meets the 30-min threshold.
        assertTrue(Gamification.quest(day, cfg).met)
        // GOAL_ONLY: only the 10 goal minutes count -> below threshold.
        assertFalse(Gamification.quest(day, cfg.copy(questMode = QuestMode.GOAL_ONLY)).met)
    }

    // --- 4.5 Streaks ---------------------------------------------------------

    @Test
    fun streak_emptyList() {
        val state = Gamification.streak(emptyList())
        assertEquals(0, state.current)
        assertEquals(0, state.longest)
    }

    @Test
    fun streak_allMet() {
        val state = Gamification.streak(days(true, true, true))
        assertEquals(3, state.current)
        assertEquals(3, state.longest)
    }

    @Test
    fun streak_singleBreakResetsCurrentKeepsLongest() {
        // met, met, unmet, met (grace 0) -> break resets, then a fresh streak of 1.
        val state = Gamification.streak(days(true, true, false, true))
        assertEquals(1, state.current)
        assertEquals(2, state.longest)
    }

    @Test
    fun streak_longestPreservedAfterBreak() {
        val state = Gamification.streak(days(true, true, true, false, true))
        assertEquals(1, state.current)
        assertEquals(3, state.longest)
    }

    @Test
    fun streak_ignoresGapsBetweenDatesUsesOrderOnly() {
        // Non-consecutive calendar dates, all met: the engine keys off order, not the dates.
        val results = listOf(
            QuestResult(LocalDate.of(2026, 1, 1), met = true),
            QuestResult(LocalDate.of(2026, 1, 5), met = true),
            QuestResult(LocalDate.of(2026, 1, 12), met = true),
        )
        val state = Gamification.streak(results)
        assertEquals(3, state.current)
        assertEquals(3, state.longest)
    }

    @Test
    fun streak_graceForgivesGapWithoutCreditingIt() {
        // met, met, unmet, met with grace 1 -> gap forgiven, following met day extends: current 3.
        val state = Gamification.streak(days(true, true, false, true), cfg.copy(streakGraceDays = 1))
        assertEquals(3, state.current)
        assertEquals(3, state.longest)
    }

    @Test
    fun streak_graceResetsAfterAMetDay() {
        // met, unmet, met, unmet, met with grace 1: each single gap is forgiven anew.
        val state = Gamification.streak(days(true, false, true, false, true), cfg.copy(streakGraceDays = 1))
        assertEquals(3, state.current)
    }

    @Test
    fun streak_failuresBeyondGraceReset() {
        // met, met, unmet, unmet with grace 1: the second consecutive miss exhausts grace.
        val state = Gamification.streak(days(true, true, false, false), cfg.copy(streakGraceDays = 1))
        assertEquals(0, state.current)
        assertEquals(2, state.longest)
    }

    // --- helpers -------------------------------------------------------------

    private fun flatGame(minutes: Int) =
        GamePlaytimeInput(gameId = "flat", minutesPlayed = minutes, completionistAverageMinutes = null)

    private fun days(vararg met: Boolean): List<QuestResult> =
        met.mapIndexed { i, m -> QuestResult(LocalDate.of(2026, 1, 1).plusDays(i.toLong()), met = m) }

    private companion object {
        const val EPS = 1e-9
        val DAY: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
