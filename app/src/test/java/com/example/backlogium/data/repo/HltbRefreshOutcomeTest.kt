package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbFailureClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbRefreshOutcomeTest {

    @Test
    fun retryPolicy_retriesWholesaleTransientFailure() {
        assertTrue(
            HltbBatchResult(
                attempted = 5,
                refreshed = 0,
                noMatch = 0,
                failed = 5,
                failureClasses = setOf(HltbFailureClass.TRANSPORT),
            ).shouldRetry,
        )
    }

    @Test
    fun retryPolicy_retriesWholesaleThrottling() {
        assertTrue(
            HltbBatchResult(
                attempted = 5,
                refreshed = 0,
                noMatch = 0,
                failed = 5,
                failureClasses = setOf(HltbFailureClass.THROTTLED),
            ).shouldRetry,
        )
    }

    @Test
    fun retryPolicy_succeedsAfterPartialProgress() {
        assertFalse(
            HltbBatchResult(
                attempted = 5,
                refreshed = 2,
                noMatch = 1,
                failed = 2,
                failureClasses = setOf(HltbFailureClass.TRANSPORT),
            ).shouldRetry,
        )
    }

    @Test
    fun retryPolicy_doesNotRepeatWholesaleParseFailureOrNoMatch() {
        assertFalse(
            HltbBatchResult(
                attempted = 5,
                refreshed = 0,
                noMatch = 0,
                failed = 5,
                failureClasses = setOf(HltbFailureClass.PARSE),
            ).shouldRetry,
        )
        assertFalse(
            HltbBatchResult(
                attempted = 5,
                refreshed = 0,
                noMatch = 5,
                failed = 0,
                failureClasses = emptySet(),
            ).shouldRetry,
        )
    }
}
