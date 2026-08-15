package com.example.backlogium.work

import androidx.work.workDataOf
import com.example.backlogium.data.hltb.HltbFailureClass
import com.example.backlogium.data.repo.HltbBatchResult
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.HltbRefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbRefreshReportingTest {

    @Test
    fun progressEncodingPreservesNoMatchAndFailureClass() {
        val noMatch = hltbBatchProgressFrom(
            workDataOf(
                HltbRefreshWorker.KEY_PROGRESS to 1,
                HltbRefreshWorker.KEY_TOTAL to 2,
                HltbRefreshWorker.KEY_CURRENT_GAME to "Nothing Found",
                HltbRefreshWorker.KEY_OUTCOME to encodeHltbOutcome(HltbRefreshOutcome.NoMatch),
            ),
        )
        val failed = hltbBatchProgressFrom(
            workDataOf(
                HltbRefreshWorker.KEY_PROGRESS to 2,
                HltbRefreshWorker.KEY_TOTAL to 2,
                HltbRefreshWorker.KEY_CURRENT_GAME to "Offline",
                HltbRefreshWorker.KEY_OUTCOME to encodeHltbOutcome(
                    HltbRefreshOutcome.Failed(HltbFailureClass.TRANSPORT),
                ),
            ),
        )

        assertEquals(HltbRefreshOutcome.NoMatch, noMatch?.outcome)
        assertEquals(
            HltbRefreshOutcome.Failed(HltbFailureClass.TRANSPORT),
            failed?.outcome,
        )
        assertEquals(2, failed?.done)
    }

    @Test
    fun progressEncodingPreservesRefreshedMatchState() {
        val progress = hltbBatchProgressFrom(
            workDataOf(
                HltbRefreshWorker.KEY_PROGRESS to 1,
                HltbRefreshWorker.KEY_TOTAL to 1,
                HltbRefreshWorker.KEY_CURRENT_GAME to "Portal",
                HltbRefreshWorker.KEY_OUTCOME to encodeHltbOutcome(
                    HltbRefreshOutcome.Refreshed(HltbMatchState.NEEDS_REVIEW),
                ),
            ),
        )

        assertEquals(
            HltbRefreshOutcome.Refreshed(HltbMatchState.NEEDS_REVIEW),
            progress?.outcome,
        )
    }

    @Test
    fun completionTextStatesZeroRefreshedAndFailureCount() {
        val text = hltbCompletionText(
            HltbBatchResult(
                attempted = 50,
                refreshed = 0,
                noMatch = 0,
                failed = 50,
                failureClasses = setOf(HltbFailureClass.TRANSPORT),
            ),
        )

        assertEquals("Refreshed 0 games; Failed 50 lookups", text)
        assertTrue(text.contains("Refreshed 0"))
    }

    @Test
    fun completionTextDistinguishesNoMatchFromFailure() {
        assertEquals(
            "Refreshed 1 game; No match for 2 games; Failed 1 lookup",
            hltbCompletionText(
                HltbBatchResult(
                    attempted = 4,
                    refreshed = 1,
                    noMatch = 2,
                    failed = 1,
                    failureClasses = setOf(HltbFailureClass.SERVER),
                ),
            ),
        )
    }

    @Test
    fun wholesaleTransientFailureDoesNotNotifyAsComplete() {
        assertFalse(
            hltbShouldNotifyComplete(
                HltbBatchResult(
                    attempted = 2,
                    refreshed = 0,
                    noMatch = 0,
                    failed = 2,
                    failureClasses = setOf(HltbFailureClass.TRANSPORT),
                ),
            ),
        )
        assertTrue(
            hltbShouldNotifyComplete(
                HltbBatchResult(
                    attempted = 2,
                    refreshed = 0,
                    noMatch = 2,
                    failed = 0,
                    failureClasses = emptySet(),
                ),
            ),
        )
    }
}
