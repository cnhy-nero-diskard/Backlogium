package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure correction rule behind recover-missed-playtime tasks 5.3/5.4: only the *operation's own*
 * shares move, a date's unrelated stored remainder is preserved, frozen credit-time eligibility
 * governs each share, integration with the compute exclusion is by exact contribution identity (never
 * a whole game) and unambiguous (app id, api name) achievement keys.
 */
class RecoveryDatedCreditTest {

    // ------------------------------------------------------------ placement share deltas

    @Test
    fun movingSharesOffOldDatesOntoNewDatesProducesSignedDeltas() {
        val before = listOf(
            PlacementShare("2026-08-12", minutes = 90, goalAtCredit = true, hiddenAtCredit = false),
            PlacementShare("2026-08-13", minutes = 15, goalAtCredit = false, hiddenAtCredit = false),
        )
        val after = listOf(
            // The 90 moved to the next day; the 15 stayed.
            PlacementShare("2026-08-13", minutes = 105, goalAtCredit = false, hiddenAtCredit = false),
        )

        val deltas = placementShareDeltas(before, after).associateBy { it.date }

        assertEquals(-90, deltas.getValue("2026-08-12").minutesDelta)
        assertEquals(-90, deltas.getValue("2026-08-12").goalMinutesDelta)
        assertEquals(90, deltas.getValue("2026-08-13").minutesDelta)
        // The moved 90 was goal-eligible on its old date and is not on the new date; the 15 that
        // stayed was never goal-eligible.
        assertEquals(0, deltas.getValue("2026-08-13").goalMinutesDelta)
    }

    @Test
    fun hiddenAtCreditSharesContributeToNeitherSide() {
        val before = listOf(
            PlacementShare("2026-08-12", minutes = 40, goalAtCredit = true, hiddenAtCredit = true),
        )
        // Hidden at credit time means its raw daily credit never existed, so re-placement changes
        // nothing on any dated row.
        val deltas = placementShareDeltas(before, emptyList())
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun anIdenticalBeforeAndAfterGraphProducesNoDeltas() {
        val graph = listOf(
            PlacementShare("2026-08-12", minutes = 30, goalAtCredit = false, hiddenAtCredit = false),
        )
        assertTrue(placementShareDeltas(graph, graph).isEmpty())
    }

    @Test
    fun onlyDatesTheOperationTouchesProduceDeltas() {
        val before = listOf(PlacementShare("2026-08-12", 90, false, false))
        // A late-evidence upgrade keeps 35 on the same date and only restores the remainder: the
        // date appears once with a net delta, and unrelated dates never appear at all.
        val after = listOf(PlacementShare("2026-08-12", 35, false, false))
        val deltas = placementShareDeltas(before, after)
        assertEquals(listOf("2026-08-12"), deltas.map { it.date })
        assertEquals(-55, deltas.single().minutesDelta)
    }

    @Test
    fun datedPlacementSharesStripSupersededAndRequireAFrozenDate() {
        val rows = listOf(
            com.example.backlogium.data.local.entity.TimedContributionPlacement(
                placementId = "a",
                contributionId = "c",
                account = "acct",
                appId = 1L,
                revision = 1L,
                sessionId = 9L,
                minutes = 35L,
                creditedDate = "2026-08-12",
                zone = "UTC",
                goalAtCredit = true,
                createdAt = 0L,
            ),
            // Superseded row: never feeds the delta.
            com.example.backlogium.data.local.entity.TimedContributionPlacement(
                placementId = "b",
                contributionId = "c",
                account = "acct",
                appId = 1L,
                revision = 1L,
                sessionId = 9L,
                minutes = 90L,
                creditedDate = "2026-08-12",
                zone = "UTC",
                superseded = true,
                createdAt = 0L,
            ),
        )

        val shares = datedPlacementShares(rows)
        assertEquals(1, shares.size)
        assertEquals(35L, shares.single().minutes)
        assertEquals(true, shares.single().goalAtCredit)
    }

    // ------------------------------------------------------------ corrective compute exclusions

    @Test
    fun correctiveBaselineSubtractsExactFreshContributionsNotTheWholeGame() {
        // 500 previously credited + 35 past-day recovery + 10 fresh current share = 545 after commit.
        val current = mapOf(440L to 545L)
        val exclusions = RecoveryEarnedInputs(
            contributions = setOf(
                RecoveryContributionSnapshot(
                    account = "acct",
                    appId = 440L,
                    baselineEpoch = 1L,
                    fromMinutes = 535L,
                    toMinutes = 545L,
                    revision = 1L,
                    authorizedMinutes = 10L,
                ),
            ),
        )

        val corrective = recoveryCorrectiveCreditedByGame(current, exclusions)

        // Exactly the 10 fresh minutes are excluded; the historical 500 and past 35 remain — the
        // game is never zeroed and never rebuilt from current visibility.
        assertEquals(535L, corrective.getValue(440L))
    }

    @Test
    fun correctiveBaselineRejectsAnExclusionThatExceedsTheCreditedTotal() {
        val thrown = runCatching {
            recoveryCorrectiveCreditedByGame(
                mapOf(440L to 100L),
                RecoveryEarnedInputs(
                    contributions = setOf(
                        RecoveryContributionSnapshot(
                            account = "acct",
                            appId = 440L,
                            baselineEpoch = 1L,
                            fromMinutes = 0L,
                            toMinutes = 200L,
                            revision = 1L,
                            authorizedMinutes = 200L,
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue("inconsistent graph must be rejected, was $thrown", thrown != null)
    }

    @Test
    fun sameApiNameAcrossTwoGamesExcludesOnlyTheClaimedApp() {
        val exclusions = RecoveryEarnedInputs(
            achievements = setOf(
                RecoveryAchievementSnapshot(appId = 1L, apiName = "win_5"),
            ),
        )
        val unlocked = listOf(
            RecoveryAchievementKey(1L, "win_5"),
            RecoveryAchievementKey(2L, "win_5"),
        )

        val corrective = recoveryCorrectiveAchievements(unlocked, exclusions)

        // The second game's identically-named achievement is untouched.
        assertEquals(listOf(RecoveryAchievementKey(2L, "win_5")), corrective)
    }

    @Test
    fun emptyExclusionsLeaveTheCurrentBaselineUntouched() {
        assertEquals(
            mapOf(440L to 545L),
            recoveryCorrectiveCreditedByGame(mapOf(440L to 545L), RecoveryEarnedInputs()),
        )
    }

    @Test
    fun mixedCorrectiveDailyInputsSubtractOnlyThisOperationsFreshShare() {
        val contribution = RecoveryContributionSnapshot(
            account = "acct", appId = 440L, baselineEpoch = 1L,
            fromMinutes = 100L, toMinutes = 110L, revision = 1L, authorizedMinutes = 10L,
        )
        val exclusions = RecoveryEarnedInputs(
            contributions = setOf(contribution),
            datedCredits = setOf(
                RecoveryDatedCreditSnapshot(
                    account = "acct", appId = 440L,
                    contributionId = contribution.identity(), revision = 1L,
                    date = "2026-08-14", minutes = 10, goalMinutes = 10,
                ),
            ),
        )
        val stored = listOf(
            com.example.backlogium.data.local.entity.DailyProgress(
                date = "2026-08-14", minutesPlayed = 145, goalMinutesPlayed = 35, questMet = true,
            ),
        )

        val corrective = recoveryCorrectiveDailyProgress(stored, exclusions)

        assertEquals(135, corrective.single().minutesPlayed)
        assertEquals(25, corrective.single().goalMinutesPlayed)
        assertEquals(true, corrective.single().questMet) // derived flag is recomputed, not edited here
        assertEquals(145, stored.single().minutesPlayed) // raw earned total remains intact
    }

    @Test
    fun missingOrUndercountedEarnedDateIsRejectedRatherThanPartiallyCorrected() {
        val contribution = RecoveryContributionSnapshot(
            account = "acct", appId = 440L, baselineEpoch = 1L,
            fromMinutes = 100L, toMinutes = 110L, revision = 1L, authorizedMinutes = 10L,
        )
        val exclusions = RecoveryEarnedInputs(
            contributions = setOf(contribution),
            datedCredits = setOf(
                RecoveryDatedCreditSnapshot(
                    account = "acct", appId = 440L,
                    contributionId = contribution.identity(), revision = 1L,
                    date = "2026-08-14", minutes = 10, goalMinutes = 0,
                ),
            ),
        )
        val result = runCatching {
            recoveryCorrectiveDailyProgress(
                listOf(com.example.backlogium.data.local.entity.DailyProgress("2026-08-14", minutesPlayed = 5)),
                exclusions,
            )
        }
        assertTrue(result.isFailure)
    }
}
