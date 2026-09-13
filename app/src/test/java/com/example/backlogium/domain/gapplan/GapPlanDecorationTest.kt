package com.example.backlogium.domain.gapplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a live count is allowed to do, and — far more importantly — what it is not.
 *
 * The headline test is [everyLookupSucceedingAndEveryLookupFailingOfferTheSameGames]. If that ever
 * fails, the same request produces different suggestions depending on whether the network answered
 * in time, and a result the player is asked to commit a month to stops meaning anything.
 *
 * One avenue closed itself when the result became one game per tier. Counts used to reorder the
 * multiplayer members inside a five-game variant, which was defensible but still meant an enriched
 * run and an offline run presented the same plan differently. With a single pick per tier there is
 * no order left to influence, and the only thing a count can now do is state a fact.
 */
class GapPlanDecorationTest {

    private val single = candidate(1, remainingMinutes = 100)
    private val multiA = candidate(2, remainingMinutes = 200, multiplayer = true)
    private val multiB = candidate(3, remainingMinutes = 300, multiplayer = true)

    @Test fun anAvailableCountStatesAFactOnTheMultiplayerPick() {
        val decorated = GapPlanDecoration.apply(
            snapshotOf(pick(PlanIntensity.FULL, multiA)),
            mapOf(2L to 1_234),
        )

        assertTrue(
            decorated.pick(PlanIntensity.FULL)!!.game!!.facts
                .contains(GapPlanFact.PlayingNow(1_234)),
        )
    }

    /** A successful zero is a real observation and is stated as one. */
    @Test fun aSuccessfulZeroIsStatedRatherThanOmitted() {
        val decorated = GapPlanDecoration.apply(
            snapshotOf(pick(PlanIntensity.FULL, multiA)),
            mapOf(2L to 0),
        )

        assertTrue(
            decorated.pick(PlanIntensity.FULL)!!.game!!.facts
                .contains(GapPlanFact.PlayingNow(0)),
        )
    }

    /** An unavailable count is not a zero. It is simply nothing the card can say. */
    @Test fun anUnavailableCountClaimsNothing() {
        val decorated = GapPlanDecoration.apply(
            snapshotOf(pick(PlanIntensity.FULL, multiA), pick(PlanIntensity.BALANCED, multiB)),
            mapOf(2L to 40),
        )

        assertTrue(
            decorated.pick(PlanIntensity.FULL)!!.game!!.facts
                .contains(GapPlanFact.PlayingNow(40)),
        )
        assertTrue(
            decorated.pick(PlanIntensity.BALANCED)!!.game!!.facts
                .none { it is GapPlanFact.PlayingNow },
        )
    }

    /**
     * The property the whole "decoration only" decision rests on. Run the same snapshot with every
     * lookup succeeding and with every lookup failing: the games, and the tiers they are in, must
     * be identical. Only the presence of a player count may differ.
     */
    @Test fun everyLookupSucceedingAndEveryLookupFailingOfferTheSameGames() {
        val snapshot = snapshotOf(
            pick(PlanIntensity.RELAXED, single),
            pick(PlanIntensity.BALANCED, multiA),
            pick(PlanIntensity.FULL, multiB),
        )

        val enriched = GapPlanDecoration.apply(snapshot, mapOf(2L to 5, 3L to 900))
        val offline = GapPlanDecoration.apply(snapshot, emptyMap())

        assertEquals(enriched.pickedAppIds, offline.pickedAppIds)
        assertEquals(
            enriched.picks.map { it.intensity to it.game?.appId },
            offline.picks.map { it.intensity to it.game?.appId },
        )
        assertEquals(
            enriched.picks.map { it.plannedMinutes },
            offline.picks.map { it.plannedMinutes },
        )
        // The one permitted difference, present in both directions.
        assertTrue(
            enriched.picks.mapNotNull { it.game }
                .any { game -> game.facts.any { it is GapPlanFact.PlayingNow } },
        )
        assertTrue(
            offline.picks.mapNotNull { it.game }
                .none { game -> game.facts.any { it is GapPlanFact.PlayingNow } },
        )
    }

    /** Decoration touches the picks in no way at all, not even for a count of a non-pick. */
    @Test fun aCountForAGameThatWasNotPickedAddsNothing() {
        val decorated = GapPlanDecoration.apply(
            snapshotOf(pick(PlanIntensity.FULL, multiA)),
            mapOf(99L to 5_000),
        )

        assertEquals(listOf(2L), decorated.pickedAppIds)
        assertTrue(
            decorated.pick(PlanIntensity.FULL)!!.game!!.facts
                .none { it is GapPlanFact.PlayingNow },
        )
    }

    /** An empty tier has nothing to annotate, and must not be disturbed. */
    @Test fun anEmptyTierIsLeftAlone() {
        val decorated = GapPlanDecoration.apply(
            snapshotOf(pick(PlanIntensity.RELAXED, null), pick(PlanIntensity.FULL, multiA)),
            mapOf(2L to 12),
        )

        assertTrue(decorated.pick(PlanIntensity.RELAXED)!!.isEmpty)
        assertEquals(listOf(2L), decorated.pickedAppIds)
    }

    /** A second pass replaces the previous count rather than stacking a second label. */
    @Test fun redecoratingReplacesRatherThanAccumulates() {
        val once = GapPlanDecoration.apply(
            snapshotOf(pick(PlanIntensity.FULL, multiA)),
            mapOf(2L to 10),
        )
        val twice = GapPlanDecoration.apply(once, mapOf(2L to 20))

        assertEquals(
            listOf(GapPlanFact.PlayingNow(20)),
            twice.pick(PlanIntensity.FULL)!!.game!!.facts
                .filterIsInstance<GapPlanFact.PlayingNow>(),
        )

        // And a later pass with nothing available clears the stale fact rather than keeping it.
        val cleared = GapPlanDecoration.apply(twice, emptyMap())
        assertTrue(
            cleared.pick(PlanIntensity.FULL)!!.game!!.facts
                .none { it is GapPlanFact.PlayingNow },
        )
    }

    /** A count never displaces the facts the pick already carried. */
    @Test fun decorationPreservesTheFactsTheCardAlreadyHad() {
        val reviewed = multiA.copy(facts = listOf(GapPlanFact.Reviews("Very Positive", 90, 100)))
        val decorated = GapPlanDecoration.apply(
            snapshotOf(pick(PlanIntensity.FULL, reviewed)),
            mapOf(2L to 9),
        )

        val facts = decorated.pick(PlanIntensity.FULL)!!.game!!.facts
        assertTrue(facts.contains(GapPlanFact.Reviews("Very Positive", 90, 100)))
        assertTrue(facts.contains(GapPlanFact.PlayingNow(9)))
        assertFalse(facts.isEmpty())
    }
}
