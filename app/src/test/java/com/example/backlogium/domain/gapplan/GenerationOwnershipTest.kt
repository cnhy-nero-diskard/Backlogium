package com.example.backlogium.domain.gapplan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The races that produce the worst possible bug in this feature: a plan the player is reading,
 * silently annotated with a different request's results.
 *
 * Cancellation alone does not prevent it, which is the reason this class exists. A coroutine
 * cancelled mid-await can still be resumed before it observes the cancellation, and a lookup that
 * completed just before its window expired is holding a real value. Both must be refused on
 * identity, not on liveness.
 */
class GenerationOwnershipTest {

    @Test fun claimingInvalidatesEveryEarlierGeneration() {
        val ownership = GenerationOwnership()

        val first = ownership.claim()
        assertTrue(ownership.isCurrent(first))

        val second = ownership.claim()
        assertFalse(ownership.isCurrent(first))
        assertTrue(ownership.isCurrent(second))
    }

    /**
     * Two identical requests are still two generations. Comparing requests instead of identities
     * would treat a regeneration-with-no-changes as the same generation and let the first one's
     * in-flight lookups publish into the second.
     */
    @Test fun regeneratingAnIdenticalRequestStillSupersedesTheFirst() {
        val ownership = GenerationOwnership()

        val first = ownership.claim()
        val second = ownership.claim()

        assertFalse(ownership.isCurrent(first))
        assertTrue(ownership.isCurrent(second))
        assertTrue(first.value != second.value)
    }

    @Test fun abandoningLeavesNothingCurrent() {
        val ownership = GenerationOwnership()
        val id = ownership.claim()

        ownership.abandon()

        assertFalse(ownership.isCurrent(id))
        // And abandonment is not a successor: a later claim still has to happen explicitly.
        assertFalse(ownership.isCurrent(GenerationId(GenerationOwnership.NONE)))
    }

    /**
     * The same-request race: a slow generation is superseded by a regeneration and then finishes.
     * It must not overwrite the successor's result.
     */
    @Test fun aSupersededGenerationCannotPublishOverItsSuccessor() = runTest {
        val ownership = GenerationOwnership()
        var published: String? = null
        val slowFinished = CompletableDeferred<Unit>()

        val first = ownership.claim()
        val slow = launch {
            delay(1_000)
            ownership.ifCurrent(first) { published = "first" }
            slowFinished.complete(Unit)
        }

        // The player regenerates while the first attempt is still in flight.
        val second = ownership.claim()
        ownership.ifCurrent(second) { published = "second" }

        slow.join()
        slowFinished.await()

        assertEquals("second", published)
    }

    /**
     * The independent-request race: two generations running concurrently, finishing out of order.
     * The one that started later owns the surface regardless of which answers first.
     */
    @Test fun theLaterGenerationOwnsTheSurfaceEvenIfTheEarlierAnswersLast() = runTest {
        val ownership = GenerationOwnership()
        val order = mutableListOf<String>()
        var published: String? = null

        val first = ownership.claim()
        val firstJob = async {
            delay(500)
            order += "first finished"
            if (ownership.ifCurrent(first) { published = "first" }) order += "first published"
        }
        val second = ownership.claim()
        val secondJob = async {
            delay(100)
            order += "second finished"
            if (ownership.ifCurrent(second) { published = "second" }) order += "second published"
        }

        secondJob.await()
        firstJob.await()

        assertEquals(
            listOf("second finished", "second published", "first finished"),
            order,
        )
        assertEquals("second", published)
    }

    /**
     * A late timeout result — the enrichment window elapsed, the plan is already complete, and the
     * answer arrives anyway — is discarded rather than applied.
     */
    @Test fun aLateEnrichmentResultIsDiscardedAfterTheSurfaceIsAbandoned() = runTest {
        val ownership = GenerationOwnership()
        var decorated: Map<Long, Int>? = null

        val id = ownership.claim()
        val enrichment = launch {
            delay(GapPlanLiveCounts.WINDOW_MILLIS + 1)
            ownership.ifCurrent(id) { decorated = mapOf(1L to 5) }
        }

        // The player navigates away before the counts arrive.
        ownership.abandon()
        enrichment.join()

        assertNull(decorated)
    }

    @Test fun ifCurrentReportsWhetherItPublished() {
        val ownership = GenerationOwnership()
        val stale = ownership.claim()
        val current = ownership.claim()

        assertFalse(ownership.ifCurrent(stale) { })
        assertTrue(ownership.ifCurrent(current) { })
    }
}
