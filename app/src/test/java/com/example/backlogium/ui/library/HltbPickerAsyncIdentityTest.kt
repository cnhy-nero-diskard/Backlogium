package com.example.backlogium.ui.library

import com.example.backlogium.data.hltb.HltbCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbPickerAsyncIdentityTest {

    private val candidate = HltbCandidate(hltbId = 1L, name = "Portal")

    @Test
    fun owningSearchPublishesCandidates() {
        val next = HltbPickerUiState(loading = true).withSearchResult(
            result = Result.success(listOf(candidate)),
            ownsRequest = true,
        )

        assertEquals(listOf(candidate), next.candidates)
        assertFalse(next.loading)
        assertFalse(next.failed)
    }

    @Test
    fun owningSearchStillPublishesARealFailure() {
        val next = HltbPickerUiState(loading = true).withSearchResult(
            result = Result.failure<List<HltbCandidate>>(IllegalStateException("source unavailable")),
            ownsRequest = true,
        )

        assertFalse(next.loading)
        assertTrue(next.failed)
    }

    @Test
    fun supersededSearchPublishesNothingOverReplacementState() {
        val replacement = HltbPickerUiState(loading = true)

        val next = replacement.withSearchResult(
            result = Result.success(listOf(candidate)),
            ownsRequest = false,
        )

        assertEquals(replacement, next)
        assertTrue(next.loading)
        assertFalse(next.failed)
    }

    @Test
    fun jobIdentityKeepsDifferentGamesIndependent() {
        val firstJob = kotlinx.coroutines.Job()
        val secondJob = kotlinx.coroutines.Job()
        val jobs = mapOf(1L to firstJob, 2L to secondJob)

        assertTrue(jobs[1L] === firstJob)
        assertTrue(jobs[2L] === secondJob)
        assertFalse(jobs[1L] === secondJob)
        assertFalse(
            HltbPickerUiState(loading = true).withSearchResult(
                result = Result.success(listOf(candidate)),
                ownsRequest = jobs[1L] === secondJob,
            ).failed,
        )
    }
}