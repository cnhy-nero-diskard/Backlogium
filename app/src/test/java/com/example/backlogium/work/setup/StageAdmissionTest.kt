package com.example.backlogium.work.setup

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The admission-handle resolution (task 3.3, review fix 1): a request's exact admitted job is
 * resolved only from the request's own work id or the recorded pre-enqueue live candidate that
 * `KEEP` retained — never from "some id that appeared" (which could be an unrelated concurrent
 * enqueue) and never from an arbitrary historical finished record.
 */
class StageAdmissionTest {

    private val requestId = UUID.randomUUID()
    private val retainedCandidate = UUID.randomUUID()
    private val unrelatedConcurrent = UUID.randomUUID()
    private val staleHistorical = UUID.randomUUID()

    @Test
    fun theRequestsOwnJobIsAdmittedAsNew() {
        val result = resolveAdmittedWork(
            requestId = requestId,
            retainedCandidateId = null,
            afterAllIds = setOf(requestId, unrelatedConcurrent),
        )
        assertEquals(AdmittedWork.New(requestId), result)
    }

    @Test
    fun aFastFinishedRequestJobIsStillNewNotAHistoricalRecord() {
        // The request's own job finished before the chain was read again — it is still the exact
        // NEW admission, even next to a stale historical record.
        val result = resolveAdmittedWork(
            requestId = requestId,
            retainedCandidateId = null,
            afterAllIds = setOf(staleHistorical, requestId),
        )
        assertEquals(AdmittedWork.New(requestId), result)
    }

    @Test
    fun keepRetainedCandidateIsReused() {
        val result = resolveAdmittedWork(
            requestId = requestId,
            retainedCandidateId = retainedCandidate,
            afterAllIds = setOf(retainedCandidate),
        )
        assertEquals(AdmittedWork.Reused(retainedCandidate), result)
    }

    @Test
    fun reusedCandidateThatFinishedFastIsStillReusedNotArbitraryHistory() {
        // The candidate was live at the moment the request arrived; KEEP retained it, and it
        // finished while resolution ran. That exact recorded candidate is the REUSED admission.
        val result = resolveAdmittedWork(
            requestId = requestId,
            retainedCandidateId = retainedCandidate,
            afterAllIds = setOf(staleHistorical, retainedCandidate),
        )
        assertEquals(AdmittedWork.Reused(retainedCandidate), result)
    }

    @Test
    fun anUnrelatedConcurrentEnqueueIsNeverAttributed() {
        // A periodic/other control enqueued another job under the same unique name while we were
        // not admitted: neither the request id nor the recorded candidate is present, so null —
        // the unrelated job must never be claimed as this request's admission.
        val result = resolveAdmittedWork(
            requestId = requestId,
            retainedCandidateId = retainedCandidate,
            afterAllIds = setOf(unrelatedConcurrent),
        )
        assertNull(result)
    }

    @Test
    fun onlyStaleHistoricalRecordsYieldNullNeverAFabricatedJob() {
        val result = resolveAdmittedWork(
            requestId = requestId,
            retainedCandidateId = null,
            afterAllIds = setOf(staleHistorical),
        )
        assertNull(result)
    }

    @Test
    fun anEmptyChainAfterAFailedEnqueueYieldsNull() {
        val result = resolveAdmittedWork(
            requestId = requestId,
            retainedCandidateId = null,
            afterAllIds = emptySet(),
        )
        assertNull(result)
    }

    @Test
    fun duplicateTapOnLiveWorkKeepsOneReusedAdmission() {
        val before = setOf(retainedCandidate)
        val after = setOf(retainedCandidate)
        assertEquals(
            AdmittedWork.Reused(retainedCandidate),
            resolveAdmittedWork(requestId, retainedCandidate, after),
        )
        // A second tap sees the same single live job: still exactly one reused admission.
        assertEquals(
            AdmittedWork.Reused(retainedCandidate),
            resolveAdmittedWork(UUID.randomUUID(), retainedCandidate, after),
        )
    }

    @Test
    fun requestTagsAreStablePrefixesOfTheRequestId() {
        assertEquals("setup_request_abc-123", setupRequestTag("abc-123"))
    }
}