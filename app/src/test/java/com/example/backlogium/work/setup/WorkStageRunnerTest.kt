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
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun runnerFor(name: String, trigger: suspend () -> Unit) = WorkStageRunner(
        workManager = workManager,
        uniqueWorkName = name,
        trigger = trigger,
        progressOf = { null },
        failureReason = REASON,
    )

    private inline fun <reified W : Worker> enqueue(name: String) {
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
