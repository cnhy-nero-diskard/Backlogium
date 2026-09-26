package com.example.backlogium.ui.history

import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudActivityStatusTest {
    @Test fun failedRequestPreservesIndependentSuccessAndObservationFreshness() {
        val afterRestart = CloudReadSummary(
            lastAttemptAt = 200_000_000L, lastOutcome = CloudReadSummaryOutcome.FAILED,
            lastSuccessAt = 100_000_000L, latestObservationAt = 50_000_000L,
            lastSuccessHasMore = true,
        )
        assertTrue(observationOlderThanDay(afterRestart, 200_000_000L))
        assertTrue(afterRestart.lastSuccessHasMore == true)
        assertEquals(100_000_000L, afterRestart.lastSuccessAt)
        assertEquals(CloudReadSummaryOutcome.FAILED, afterRestart.lastOutcome)
        assertFalse(observationOlderThanDay(afterRestart.copy(latestObservationAt = null), 200_000_000L))
        assertFalse(observationOlderThanDay(afterRestart.copy(latestObservationAt = 200_000_000L), 200_000_000L))
    }

    @Test fun nextStalenessRefreshUsesExactTwentyFourHourBoundary() {
        val observationAt = 100_000L
        val summary = CloudReadSummary(latestObservationAt = observationAt)
        val oneDay = 24L * 60L * 60L * 1000L

        assertEquals(1L, observationMillisUntilStale(summary, observationAt + oneDay))
        assertNull(observationMillisUntilStale(summary, observationAt + oneDay + 1L))
        assertNull(observationMillisUntilStale(CloudReadSummary(), observationAt))
    }
}
