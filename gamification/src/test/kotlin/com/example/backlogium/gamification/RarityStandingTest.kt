package com.example.backlogium.gamification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only tests for the pigeonhole bound and its one-sided display formatting. */
class RarityStandingTest {

    @Test
    fun workedCase_usesTheTightestHandComputedSweep() {
        val rates = mutableListOf<Double>().apply {
            repeat(10) { add(1.0) }
            add(1.2) // The 11 rarest sum to 11.2.
            add(2.1) // The 12 rarest sum to 13.3.
            repeat(28) { add(100.0) }
        }
        val result = RarityStanding.derive(
            RarityStanding.Input(40, 30, rates.map { it }),
        )

        val sorted = rates.sorted()
        val boundOne = sorted.take(11).sum() / 1
        val boundTwo = sorted.take(12).sum() / 2
        assertEquals(11.2, boundOne, EPS)
        assertEquals(6.65, boundTwo, EPS)
        assertEquals(6.65, result.ceilingPercent!!, EPS)
    }

    @Test
    fun bruteForce_ownerPopulations_neverExceedTheReturnedBound() {
        val ownerCount = 4
        val rates = listOf(100.0, 75.0, 50.0, 25.0)
        val result = RarityStanding.derive(
            RarityStanding.Input(4, 3, rates.map { it }),
        )
        val bound = result.ceilingPercent!!
        val options = rates.map { rate ->
            ownerSets(ownerCount, (rate / 100.0 * ownerCount).toInt())
        }

        for (configuration in configurations(options)) {
            val ownersAtOrAbove = (0 until ownerCount).count { owner ->
                configuration.count { achievementOwners -> owner in achievementOwners } >= 3
            }
            val trueShare = ownersAtOrAbove * 100.0 / ownerCount
            assertTrue(
                "true share $trueShare exceeded bound $bound for $configuration",
                trueShare <= bound + EPS,
            )
        }
    }

    @Test
    fun fullCompletion_equalsTheRarestKnownRate() {
        val result = RarityStanding.derive(
            RarityStanding.Input(3, 3, listOf(40.0, 0.0, 20.0)),
        )

        assertEquals(0.0, result.ceilingPercent!!, EPS)
    }

    @Test
    fun noAchievementsUnlocked_hasNoBound() {
        val result = RarityStanding.derive(
            RarityStanding.Input(4, 0, listOf(10.0, 20.0, 30.0, 40.0)),
        )

        assertEquals(null, result.ceilingPercent)
    }

    @Test
    fun missingRates_needAtLeastMissingPlusOneKnownRates() {
        val noRates = RarityStanding.derive(
            RarityStanding.Input(5, 2, listOf(null, null, null, null, null)),
        )
        val exactlyEnough = RarityStanding.derive(
            RarityStanding.Input(5, 2, listOf(10.0, 10.0, 10.0, 10.0, null)),
        )

        assertEquals(null, noRates.ceilingPercent)
        assertEquals(40.0, exactlyEnough.ceilingPercent!!, EPS)
    }

    @Test
    fun partialRates_keepMissingCountFromTheFullAchievementTotal() {
        // N=5, n=2 => m=3, so the four known rates produce 40%. Recomputing N=4 would
        // incorrectly use m=2 and return 30%.
        val result = RarityStanding.derive(
            RarityStanding.Input(5, 2, listOf(10.0, 10.0, 10.0, 10.0)),
        )

        assertEquals(40.0, result.ceilingPercent!!, EPS)
    }

    @Test
    fun zeroRate_isAValidFiniteBound() {
        val result = RarityStanding.derive(
            RarityStanding.Input(1, 1, listOf(0.0)),
        )

        assertNotNull(result.ceilingPercent)
        assertTrue(result.ceilingPercent!! >= 0.0)
        assertTrue(result.ceilingPercent!!.isFinite())
    }

    @Test
    fun ratesWithSumAboveOneHundred_areClamped() {
        val result = RarityStanding.derive(
            RarityStanding.Input(3, 1, listOf(60.0, 60.0, 60.0)),
        )

        assertEquals(100.0, result.ceilingPercent!!, EPS)
    }

    @Test
    fun singleAndTwoAchievementGames_areHandled() {
        val single = RarityStanding.derive(
            RarityStanding.Input(1, 1, listOf(2.0)),
        )
        val two = RarityStanding.derive(
            RarityStanding.Input(2, 1, listOf(10.0, 20.0)),
        )

        assertEquals(2.0, single.ceilingPercent!!, EPS)
        assertEquals(30.0, two.ceilingPercent!!, EPS)
    }

    @Test
    fun ceilingFormatting_roundsUpAndKeepsTheDisplayFloor() {
        assertEquals("6.7", RarityStanding.formatCeiling(6.65))
        assertEquals("12", RarityStanding.formatCeiling(11.2))
        assertEquals("0.1", RarityStanding.formatCeiling(0.0))
        assertEquals("10", RarityStanding.formatCeiling(10.0))
        assertEquals("100", RarityStanding.formatCeiling(99.1))
    }

    @Test
    fun ceilingFormatting_neverDisplaysBelowTheTrueBound() {
        listOf(0.0, 0.01, 0.1, 1.01, 6.65, 9.99, 10.01, 50.001, 99.1).forEach { bound ->
            val displayed = RarityStanding.formatCeiling(bound).toDouble()
            assertTrue("$displayed was below $bound", displayed + EPS >= bound)
        }
    }

    private fun ownerSets(ownerCount: Int, ownedCount: Int): List<Set<Int>> =
        (0 until (1 shl ownerCount))
            .filter { mask -> Integer.bitCount(mask) == ownedCount }
            .map { mask ->
                (0 until ownerCount).filter { owner -> mask and (1 shl owner) != 0 }.toSet()
            }

    private fun configurations(options: List<List<Set<Int>>>): List<List<Set<Int>>> {
        if (options.isEmpty()) return listOf(emptyList())
        return options.first().flatMap { achievementOwners ->
            configurations(options.drop(1)).map { rest -> listOf(achievementOwners) + rest }
        }
    }

    private companion object {
        const val EPS = 1e-9
    }
}
