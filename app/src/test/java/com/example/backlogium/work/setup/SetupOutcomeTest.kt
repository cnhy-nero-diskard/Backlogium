package com.example.backlogium.work.setup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored form of an outcome, and how a stored set is narrowed to what this build registers.
 *
 * Outcomes are persisted, so this codec is a compatibility surface: a value written by a later
 * version of the app has to read as "never run" rather than stopping setup from rendering at all.
 */
class SetupOutcomeTest {

    @Test
    fun everyOutcomeSurvivesARoundTrip() {
        val outcomes = listOf(
            SetupOutcome.NeverRun,
            SetupOutcome.Succeeded,
            SetupOutcome.Skipped,
            SetupOutcome.Failed("couldn't reach Steam"),
        )
        outcomes.forEach { outcome ->
            assertEquals(outcome, decodeSetupOutcome(encodeSetupOutcome(outcome)))
        }
    }

    @Test
    fun aFailureReasonContainingTheSeparatorSurvives() {
        val outcome = SetupOutcome.Failed("failed: twice, actually")
        assertEquals(outcome, decodeSetupOutcome(encodeSetupOutcome(outcome)))
    }

    @Test
    fun anUnrecognizedStoredValueReadsAsNeverRun() {
        assertEquals(SetupOutcome.NeverRun, decodeSetupOutcome("some_future_outcome"))
        assertEquals(SetupOutcome.NeverRun, decodeSetupOutcome(null))
    }

    @Test
    fun projectionDropsUnknownIdsAndFillsMissingOnes() {
        val projected = projectSetupOutcomes(
            stored = mapOf(
                "known" to SetupOutcome.Succeeded,
                "gone" to SetupOutcome.Failed("from a stage that no longer exists"),
            ),
            registeredIds = listOf("known", "added_later"),
        )

        assertEquals(setOf("known", "added_later"), projected.keys)
        assertEquals(SetupOutcome.Succeeded, projected["known"])
        // A stage registered after the user already completed setup has no outcome, and "never run"
        // is the honest answer rather than a missing row or a fabricated success.
        assertEquals(SetupOutcome.NeverRun, projected["added_later"])
    }
}
