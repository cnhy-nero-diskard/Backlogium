package com.example.backlogium.domain.gapplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local edits to a preview.
 *
 * The property under test is that an edit changes exactly what the player asked it to. A preview
 * that reshuffled its other choices whenever one was changed would not be a preview of anything,
 * and the membership the player eventually accepts has to be the membership they were looking at.
 */
class GapPlanEditingTest {

    private val pool = listOf(
        candidate(1, remainingMinutes = 300),
        candidate(2, remainingMinutes = 300),
        candidate(3, remainingMinutes = 300),
        candidate(4, remainingMinutes = 900),
        candidate(5, remainingMinutes = 100),
    )

    private val variant = GapPlanVariant(
        intensity = PlanIntensity.BALANCED,
        budgetMinutes = 1_000,
        members = listOf(pool[0], pool[1], pool[2]),
    )

    @Test fun removingAMemberUpdatesPlannedAndReserveAndLeavesTheOthersAlone() {
        val updated = GapPlanEditing.remove(variant, appId = 2)

        assertEquals(listOf(1L, 3L), updated.members.map { it.appId })
        assertEquals(600, updated.plannedMinutes)
        assertEquals(400, updated.reserveMinutes)
        // The original is untouched: edits return a new variant rather than mutating one.
        assertEquals(3, variant.members.size)
        assertEquals(900, variant.plannedMinutes)
    }

    @Test fun removingAGameThatIsNotThereChangesNothing() {
        assertEquals(variant, GapPlanEditing.remove(variant, appId = 99))
    }

    @Test fun aValidReplacementKeepsTheOtherChoicesAndTheirPositions() {
        val updated = GapPlanEditing.replace(variant, pool, removeAppId = 2, addAppId = 5).getOrThrow()

        assertEquals(listOf(1L, 5L, 3L), updated.members.map { it.appId })
        assertEquals(700, updated.plannedMinutes)
        assertEquals(300, updated.reserveMinutes)
    }

    @Test fun aReplacementThatWouldExceedTheBudgetIsRejected() {
        val error = GapPlanEditing.replace(variant, pool, removeAppId = 2, addAppId = 4)
            .exceptionOrNull()

        assertEquals(GapPlanEditError.EXCEEDS_BUDGET, (error as GapPlanEditException).error)
    }

    @Test fun aDuplicateReplacementIsRejected() {
        val error = GapPlanEditing.replace(variant, pool, removeAppId = 2, addAppId = 3)
            .exceptionOrNull()

        assertEquals(GapPlanEditError.ALREADY_A_MEMBER, (error as GapPlanEditException).error)
    }

    @Test fun replacingSomethingThatIsNotAMemberOrWithSomethingNotEligibleIsRejected() {
        assertEquals(
            GapPlanEditError.NOT_A_MEMBER,
            (
                GapPlanEditing.replace(variant, pool, removeAppId = 99, addAppId = 5)
                    .exceptionOrNull() as GapPlanEditException
                ).error,
        )
        assertEquals(
            GapPlanEditError.NOT_ELIGIBLE,
            (
                GapPlanEditing.replace(variant, pool, removeAppId = 2, addAppId = 99)
                    .exceptionOrNull() as GapPlanEditException
                ).error,
        )
    }

    /**
     * Offered, not merely validated. A replacement the budget cannot hold is filtered out here, so
     * it is never presented as a choice and then refused after the player picks it.
     */
    @Test fun onlyBudgetValidNonMemberCandidatesAreOfferedAsReplacements() {
        val offered = GapPlanEditing.replacementsFor(variant, pool, removeAppId = 2)

        // 4 costs 900 against 400 reserve plus the 300 freed — 700 of headroom — so it is out.
        assertEquals(listOf(5L), offered.map { it.appId })
        assertTrue(offered.none { it.appId in variant.members.map(GapPlanCandidate::appId) })
    }

    @Test fun replacementsForAGameThatIsNotAMemberAreEmpty() {
        assertEquals(emptyList<GapPlanCandidate>(), GapPlanEditing.replacementsFor(variant, pool, 99))
    }

    /**
     * Validation reads the snapshot's *retained* pool, not a fresh derivation. Re-deriving here
     * would let an enrichment that landed since generation silently change which swaps are on
     * offer, under a player who is midway through choosing one.
     */
    @Test fun editsAreValidatedAgainstTheRetainedPoolRatherThanAnythingNewer() {
        val shrunkPool = pool.filterNot { it.appId == 5L }

        assertEquals(
            GapPlanEditError.NOT_ELIGIBLE,
            (
                GapPlanEditing.replace(variant, shrunkPool, removeAppId = 2, addAppId = 5)
                    .exceptionOrNull() as GapPlanEditException
                ).error,
        )
        // With the pool the snapshot actually retained, the same swap is fine.
        assertTrue(GapPlanEditing.replace(variant, pool, removeAppId = 2, addAppId = 5).isSuccess)
    }

    /** A sequence of edits never disturbs membership it was not asked about. */
    @Test fun acceptedMembershipStaysStableAcrossSeveralEdits() {
        val afterRemove = GapPlanEditing.remove(variant, appId = 3)
        val afterReplace = GapPlanEditing
            .replace(afterRemove, pool, removeAppId = 2, addAppId = 5)
            .getOrThrow()

        assertEquals(listOf(1L, 5L), afterReplace.members.map { it.appId })
        // Game 1 was never mentioned in either edit and is byte-identical to the original entry.
        assertEquals(variant.members.first(), afterReplace.members.first())
    }
}
