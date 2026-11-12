package com.example.backlogium.work.setup

import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.CoroutineWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * How a wrapped WorkManager job's terminal state becomes a stage outcome.
 *
 * A cancelled job is deliberately *not* a success. Someone who cancelled an asset download from
 * Settings should see that stage as unfinished, and a Settings list that reported it as done would
 * be the one place they would go to find out otherwise.
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

    @Test
    fun succeededWorkIsASucceededStage() = runTest {
        val runner = runnerFor(NAME_OK) { enqueue<SucceedingWorker>(NAME_OK) }
        assertEquals(SetupOutcome.Succeeded, runner.run { })
    }

    @Test
    fun failedWorkIsAFailedStageWithTheStagesOwnReason() = runTest {
        val runner = runnerFor(NAME_BAD) { enqueue<FailingWorker>(NAME_BAD) }
        assertEquals(SetupOutcome.Failed(REASON), runner.run { })
    }

    @Test
    fun cancelledWorkIsNotReportedAsSuccess() = runTest {
        val runner = runnerFor(NAME_CANCELLED) {
            // Constrained so it cannot run before the cancel lands.
            workManager.enqueueUniqueWork(
                NAME_CANCELLED,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SucceedingWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
                    )
                    .build(),
            )
            workManager.cancelUniqueWork(NAME_CANCELLED)
        }

        val outcome = runner.run { }
        assertEquals(SetupOutcome.Failed("Cancelled before it finished"), outcome)
    }

    @Test
    fun recoveryObservesThePersistedWorkIdWithoutTriggeringAnotherJob() = runTest {
        workManager.enqueueUniqueWork(
            NAME_RECOVER,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SucceedingWorker>().build(),
        )
        val workId = workManager.getWorkInfosForUniqueWorkFlow(NAME_RECOVER)
            .first { infos -> infos.isNotEmpty() }
            .single()
            .id
        var triggered = false
        val runner = runnerFor(NAME_RECOVER) { triggered = true }

        assertEquals(
            SetupOutcome.Succeeded,
            runner.recover(workId.toString()) { }
        )
        assertFalse("recovery must not enqueue a duplicate job", triggered)
    }

    @Test
    fun setupReusesManualWorkWhilePeriodicAndPresenceAreRunning() = runTest {
        HeldSetupWorker.gate = CompletableDeferred()
        val periodic = PeriodicWorkRequestBuilder<HeldSetupWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        enqueue<HeldSetupWorker>("presence")
        val manual = OneTimeWorkRequestBuilder<HeldSetupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        workManager.enqueueUniqueWork("manual", ExistingWorkPolicy.KEEP, manual)
        val activities = mutableListOf<SetupStageActivity>()
        val admitted = CompletableDeferred<String>()
        val waiting = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        val attempt = async {
            runnerFor("manual") { enqueue<SucceedingWorker>("manual") }.run(
                onProgress = {
                    activities += it.activity
                    if (it.activity == SetupStageActivity.WAITING) waiting.complete(Unit)
                    if (it.activity == SetupStageActivity.RUNNING) running.complete(Unit)
                },
                onWorkStarted = { admitted.complete(it) },
            )
        }
        assertEquals(manual.id.toString(), admitted.await())
        waiting.await()
        assertFalse(attempt.isCompleted)
        assertTrue(SetupStageActivity.WAITING in activities)
        assertEquals(1, workManager.getWorkInfosForUniqueWorkFlow("manual").first().size)
        assertEquals(WorkInfo.State.RUNNING, workManager.getWorkInfosForUniqueWorkFlow("periodic")
            .first { it.any { info -> info.state == WorkInfo.State.RUNNING } }.single().state)
        assertEquals(WorkInfo.State.RUNNING,
            workManager.getWorkInfosForUniqueWorkFlow("presence")
                .first { it.any { info -> info.state == WorkInfo.State.RUNNING } }.single().state)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(manual.id)
        running.await()
        assertTrue(SetupStageActivity.RUNNING in activities)
        HeldSetupWorker.gate.complete(Unit)
        assertEquals(SetupOutcome.Succeeded, attempt.await())
        workManager.cancelAllWork().result.get()
    }

    @Test
    fun retryBackoffRemainsOutstandingAndRecoveryUsesTheSameId() = runTest {
        val request = OneTimeWorkRequestBuilder<RetrySetupWorker>().build()
        workManager.enqueueUniqueWork("retry", ExistingWorkPolicy.KEEP, request)
        val activities = mutableListOf<SetupStageProgress>()
        val attempt = backgroundScope.async {
            runnerFor("retry") { error("recovery must not trigger") }
                .recover(request.id.toString()) { activities += it }
        }
        runCurrent()
        assertFalse(attempt.isCompleted)
        assertTrue(activities.any { it.activity == SetupStageActivity.RETRY_BACKOFF && it.attemptCount > 0 })
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(request.id).get().state)
        workManager.cancelUniqueWork("retry").result.get()
        assertEquals(SetupOutcome.Failed("Cancelled before it finished"), attempt.await())
    }

    @Test
    fun domainFailureInSuccessfulWorkerOutputIsNotReportedAsCompleted() = runTest {
        val runner = WorkStageRunner(workManager, "unreadable", { enqueue<UnreadableSetupWorker>("unreadable") },
            progressOf = { null }, failureReason = REASON,
            successOutcome = { data -> data.getString("reason")?.let { SetupOutcome.Failed(it) }
                ?: SetupOutcome.Succeeded })
        assertEquals(SetupOutcome.Failed("Library is private"), runner.run { })
    }

    @Test
    fun queuedRetryCanCompleteWithoutBecomingAFailedSetupStage() = runTest {
        val request = OneTimeWorkRequestBuilder<RetryOnceSetupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        workManager.enqueueUniqueWork("retry-once", ExistingWorkPolicy.KEEP, request)
        val activities = mutableListOf<SetupStageActivity>()
        val attempt = backgroundScope.async {
            runnerFor("retry-once") { error("must recover") }.recover(request.id.toString()) {
                activities += it.activity
            }
        }
        runCurrent()
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(request.id)
        runCurrent()
        assertTrue(SetupStageActivity.RETRY_BACKOFF in activities)
        assertFalse(attempt.isCompleted)
        driver.setAllConstraintsMet(request.id)
        assertEquals(SetupOutcome.Succeeded, attempt.await())
    }

    private fun runnerFor(name: String, trigger: suspend () -> Unit) = WorkStageRunner(
        workManager = workManager,
        uniqueWorkName = name,
        trigger = trigger,
        progressOf = { null },
        failureReason = REASON,
    )

    private inline fun <reified W : androidx.work.ListenableWorker> enqueue(name: String) {
        workManager.enqueueUniqueWork(
            name,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<W>().build(),
        )
    }

    private companion object {
        const val NAME_OK = "runner_test_ok"
        const val NAME_BAD = "runner_test_bad"
        const val NAME_CANCELLED = "runner_test_cancelled"
        const val NAME_RECOVER = "runner_test_recover"
        const val REASON = "the stage's own reason"
    }
}

class SucceedingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}

class FailingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.failure()
}

class HeldSetupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { gate.await(); return Result.success() }
    companion object { var gate = CompletableDeferred<Unit>() }
}

class RetrySetupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.retry()
}

class RetryOnceSetupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (runAttemptCount == 0) Result.retry() else Result.success()
}

class UnreadableSetupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success(androidx.work.workDataOf("reason" to "Library is private"))
}
