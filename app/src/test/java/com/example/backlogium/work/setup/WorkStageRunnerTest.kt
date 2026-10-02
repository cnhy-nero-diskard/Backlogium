package com.example.backlogium.work.setup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.work.SteamAssetDownloadWorker
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The admission seam (task 3.3) and live observation (task 3.4) of a wrapped WorkManager job,
 * over a real WorkManager, using exact UUID request/work identities only.
 *
 * A queued retry is [SetupOperationState.RetryScheduled], never a terminal failure; cancellation
 * stays [SetupOperationState.Cancelled]; a worker's own failure reason (HLTB) remains attributable;
 * a job that no longer exists is [SetupOperationState.RecoveryRequired]. Admission always names the
 * exact job — the request's own id or the recorded live `KEEP` candidate — and exact recovery
 * (`locate`) resolves only the request id+tag or a still-live recorded candidate.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WorkStageRunnerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    // ---------------------------------------------------------------------------------------
    // Admission
    // ---------------------------------------------------------------------------------------

    @Test
    fun aFreshAdmissionIsNewWithAUsableInitialState() = runTest {
        val requestId = uuid()
        val runner = runnerFor(NAME, constrained = true)
        val admission = runner.admit(requestId)

        val work = admission as StageAdmission.Work
        assertEquals(AdmissionKind.NEW, work.kind)
        assertEquals(NAME, work.uniqueWorkName)
        assertEquals("the request's own job is admitted exactly", requestId, work.workId.toString())
        // The fresh job sits ENQUEUED behind its network constraint: waiting, never a failure.
        assertTrue(work.initialState is SetupOperationState.Waiting)
        assertNotNull(workManager.getWorkInfosForUniqueWork(NAME).get().firstOrNull())
    }

    @Test
    fun existingLiveWorkIsAdmittedAsReusedWithoutADuplicate() = runTest {
        workManager.enqueueUniqueWork(
            NAME,
            ExistingWorkPolicy.KEEP,
            constrainedRequest().build(),
        )
        val existing = workManager.getWorkInfosForUniqueWorkFlow(NAME).first().single().id

        val runner = runnerFor(NAME, constrained = true)
        val admission = runner.admit(uuid())

        val work = admission as StageAdmission.Work
        assertEquals(AdmissionKind.REUSED, work.kind)
        assertEquals(existing, work.workId)
        // Duplicate taps keep exactly one job on the chain.
        assertEquals(1, workManager.getWorkInfosForUniqueWork(NAME).get().size)
    }

    @Test
    fun aStaleFinishedRecordIsNeverChosenAsTheAdmission() = runTest {
        // A previous run finished and left a historical SUCCEEDED record under this unique name.
        workManager.enqueueUniqueWork(
            NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SucceedingWorker>().build(),
        )
        val staleId = workManager.awaitFinishedId(NAME)

        val runner = runnerFor(NAME, constrained = true)
        val admission = runner.admit(uuid())

        val work = admission as StageAdmission.Work
        assertEquals(AdmissionKind.NEW, work.kind)
        assertNotEquals("never attach an arbitrary historical finished job", staleId, work.workId)
    }

    @Test
    fun anAdmissionThatCannotBeEstablishedNeedsRecovery() = runTest {
        // The scheduler refused or failed to admit anything: no job appears on the chain.
        val runner = WorkStageRunner(
            workManager = workManager,
            uniqueWorkName = NAME,
            admitWork = { null },
            progressOf = { null },
            failureReason = REASON,
        )

        val admission = runner.admit(uuid())
        assertTrue(admission is StageAdmission.NeedsRecovery)
    }

    // ---------------------------------------------------------------------------------------
    // Live observation
    // ---------------------------------------------------------------------------------------

    @Test
    fun aQueuedRetryIsRetryScheduledNotATerminalFailure() = runTest {
        workManager.enqueueUniqueWork(
            NAME_RETRY,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RetryingWorker>()
                // A generous first backoff keeps the queued retry in ENQUEUED for the whole test
                // instead of the synchronous executor racing it into another attempt.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build(),
        )
        val id = workManager.getWorkInfosForUniqueWorkFlow(NAME_RETRY).first()
            .single().id
        workManager.getWorkInfosForUniqueWorkFlow(NAME_RETRY)
            .first { infos ->
                infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
            }

        val runner = runnerFor(NAME_RETRY, constrained = false)
        val state = runner.observe(id.toString()).first()

        assertTrue("a queued retry is retry scheduled, not terminal", state is SetupOperationState.RetryScheduled)
        assertFalse(state.isTerminal)
        assertTrue((state as SetupOperationState.RetryScheduled).attempt > 0)
    }

    @Test
    fun aCancelledJobIsReportedAsCancelledDistinctly() = runTest {
        workManager.enqueueUniqueWork(NAME_CANCELLED, ExistingWorkPolicy.KEEP, constrainedRequest().build())
        val id = workManager.getWorkInfosForUniqueWorkFlow(NAME_CANCELLED).first().single().id
        workManager.cancelWorkById(id)

        val runner = runnerFor(NAME_CANCELLED, constrained = false)
        val state = runner.observe(id.toString()).first()

        assertEquals(SetupOperationState.Cancelled, state)
        assertTrue("cancellation is its own distinct terminal outcome", state.isTerminal)
    }

    @Test
    fun theWorkersAttributableFailureReasonIsReported() = runTest {
        // HLTB-style terminal failure: the worker returns Result.failure with its own reason.
        workManager.enqueueUniqueWork(
            NAME_FAIL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FailingWithReasonWorker>().build(),
        )
        val id = workManager.getWorkInfosForUniqueWorkFlow(NAME_FAIL).first().single().id

        val runner = runnerFor(NAME_FAIL, constrained = false)
        val state = runner.observe(id.toString()).first()

        assertTrue(state is SetupOperationState.Failed)
        assertEquals("dataset failed to validate", (state as SetupOperationState.Failed).reason)
    }

    @Test
    fun aMissingOrPrunedJobIsRecoveryRequiredNotSuccess() = runTest {
        val runner = runnerFor(NAME, constrained = false)
        val state = runner.observe(UUID.randomUUID().toString()).first()

        assertTrue(state is SetupOperationState.RecoveryRequired)
        assertFalse(state.isTerminal)
    }

    @Test
    fun artworkExplicitZeroTotalIsASuccessfulZeroItemResultWithReRunExplanation() = runTest {
        workManager.enqueueUniqueWork(
            NAME_ZERO, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ZeroTotalWorker>().build(),
        )
        val id = workManager.getWorkInfosForUniqueWorkFlow(NAME_ZERO).first().single().id

        val runner = WorkStageRunner(
            workManager = workManager,
            uniqueWorkName = NAME_ZERO,
            admitWork = { null },
            progressOf = { null },
            failureReason = REASON,
            classifyTerminal = { _, info -> steamAssetsTerminalState(REASON)(info) },
        )
        val state = runner.observe(id.toString()).first()

        assertTrue("an explicit zero inventory is a returned result, not a failure", state is SetupOperationState.Succeeded)
        val detail = (state as SetupOperationState.Succeeded).detail
        assertTrue("the result explains the zero available items and the re-run path",
            detail != null && detail.contains("No artwork") && detail.contains("re-run"))
    }

    @Test
    fun artworkMissingLegacyTotalIsNeverInventedAsAZeroGameDownload() = runTest {
        workManager.enqueueUniqueWork(
            NAME_NO_TOTAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PlainSuccessfulWorker>().build(),
        )
        val id = workManager.getWorkInfosForUniqueWorkFlow(NAME_NO_TOTAL).first().single().id

        val runner = WorkStageRunner(
            workManager = workManager,
            uniqueWorkName = NAME_NO_TOTAL,
            admitWork = { null },
            progressOf = { null },
            failureReason = REASON,
            classifyTerminal = { _, info -> steamAssetsTerminalState(REASON)(info) },
        )
        val state = runner.observe(id.toString()).first()

        // A finishing job without KEY_TOTAL evidence cannot claim a populated (or zero) download:
        // the stage asks for an explicit re-run instead.
        assertTrue(state is SetupOperationState.RecoveryRequired)
        assertFalse((state as SetupOperationState.RecoveryRequired).reason.isNullOrBlank())
    }

    // ---------------------------------------------------------------------------------------
    // Interrupted-admission recovery by exact request identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun locateFindsTheExactTaggedRequestJobAsNew() = runTest {
        val requestId = uuid()
        workManager.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            constrainedRequest().setId(UUID.fromString(requestId))
                .addTag(setupRequestTag(requestId)).build(),
        )

        val runner = runnerFor(NAME, constrained = true)
        val located = runner.locate(requestId, null)

        assertEquals(AdmittedWork.New(UUID.fromString(requestId)), located)
        // Recovery never enqueues a duplicate: the chain still holds exactly one job.
        assertEquals(1, workManager.getWorkInfosForUniqueWork(NAME).get().size)
    }

    @Test
    fun locateIgnoresAJobCarryingTheRequestIdButNoRequestTag() = runTest {
        // A foreign/concurrent job that happens to reuse the id is not this request's admission
        // without its setup tag.
        val requestId = uuid()
        workManager.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            constrainedRequest().setId(UUID.fromString(requestId)).build(),
        )

        val runner = runnerFor(NAME, constrained = true)
        assertNull(runner.locate(requestId, null))
        assertNull("without a tag the id alone cannot prove this request's admission", runner.locate(requestId, null))
    }

    @Test
    fun locateNeverFallsBackToAnArbitraryLiveJob() = runTest {
        val requestId = uuid()
        workManager.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            constrainedRequest().build(),
        )

        val runner = runnerFor(NAME, constrained = true)
        assertNull("no tag, no recorded candidate: exact recovery yields nothing, not a guess", runner.locate(requestId, null))
        assertEquals(1, workManager.getWorkInfosForUniqueWork(NAME).get().size)
    }

    @Test
    fun locateRecoversTheRecordedLiveCandidateWhenTheTagIsGone() = runTest {
        // KEEP-reuse carries no setup tag: only the candidate recorded before enqueue can prove it,
        // and only while the candidate is still live.
        workManager.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            constrainedRequest().build(),
        )
        val candidate = workManager.getWorkInfosForUniqueWorkFlow(NAME).first().single().id
        val runner = runnerFor(NAME, constrained = true)

        val located = runner.locate(uuid(), candidate)

        assertEquals(AdmittedWork.Reused(candidate), located)
    }

    @Test
    fun aTerminalCandidateAloneCannotProveKEEP() = runTest {
        val requestId = uuid()
        workManager.enqueueUniqueWork(
            NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SucceedingWorker>().build(),
        )
        val finishedCandidate = workManager.awaitFinishedId(NAME)

        val runner = runnerFor(NAME, constrained = true)
        // A finished candidate may have been superseded by a different control's admission; it
        // never proves THIS request was retained — explicit recovery instead of a guess.
        assertNull(runner.locate(requestId, finishedCandidate))
    }

    @Test
    fun locateWithNoRecordedRequestYieldsNothingNotADuplicate() = runTest {
        val runner = runnerFor(NAME, constrained = true)
        assertEquals(runner.locate(uuid(), null), null)
        assertTrue(workManager.getWorkInfosForUniqueWork(NAME).get().isEmpty())
    }

    private fun runnerFor(name: String, constrained: Boolean) = WorkStageRunner(
        workManager = workManager,
        uniqueWorkName = name,
        admitWork = { requestId -> admitFor(name, constrained, requestId) },
        progressOf = { null },
        failureReason = REASON,
    )

    /** The scheduler admission core, mirroring SyncScheduler's exact-id seam over [workManager]. */
    private suspend fun admitFor(
        name: String,
        constrained: Boolean,
        requestId: String,
    ): AdmittedWork? {
        val requestUuid = UUID.fromString(requestId)
        val before = workManager.getWorkInfosForUniqueWorkFlow(name).first()
        val retainedCandidate = before.firstOrNull { !it.state.isFinished }?.id
        val request = (if (constrained) constrainedRequest() else plainRequest())
            .setId(requestUuid)
            .addTag(setupRequestTag(requestId))
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        val after = workManager.getWorkInfosForUniqueWorkFlow(name).first()
        return resolveAdmittedWork(requestUuid, retainedCandidate, after.map { it.id }.toSet())
    }

    private fun constrainedRequest(): OneTimeWorkRequest.Builder =
        OneTimeWorkRequestBuilder<SucceedingWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )

    private fun plainRequest(): OneTimeWorkRequest.Builder =
        OneTimeWorkRequestBuilder<SucceedingWorker>()

    private suspend fun WorkManager.awaitFinishedId(name: String): UUID {
        return getWorkInfosForUniqueWorkFlow(name)
            .first { infos -> infos.singleOrNull()?.state?.isFinished == true }
            .single()
            .id
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private companion object {
        const val NAME = "runner_admission_test"
        const val NAME_RETRY = "runner_test_retry"
        const val NAME_CANCELLED = "runner_test_cancelled"
        const val NAME_FAIL = "runner_test_fail"
        const val NAME_ZERO = "runner_test_artwork_zero"
        const val NAME_NO_TOTAL = "runner_test_artwork_no_total"
        const val REASON = "the stage's own reason"
    }
}

class SucceedingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}

class RetryingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.retry()
}

class FailingWithReasonWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.failure(
        workDataOf(HltbDatasetWorker.KEY_FAILURE_REASON to "dataset failed to validate"),
    )
}

class ZeroTotalWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success(
        workDataOf(SteamAssetDownloadWorker.KEY_TOTAL to 0, SteamAssetDownloadWorker.KEY_PROCESSED to 0),
    )
}

class PlainSuccessfulWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}