package com.example.backlogium.work

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.AchievementAdmissionWork
import com.example.backlogium.data.local.entity.Game
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementAdmissionSchedulerTest {
    private lateinit var database: BacklogiumDatabase
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerAchievementAdmissionScheduler

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        database = androidx.room.Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        scheduler = WorkManagerAchievementAdmissionScheduler(context, database.achievementAdmissionDao())
    }

    @After
    fun tearDown() {
        database.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun delayedWakesUseSeparateTimerChainsFromImmediateDrains() = runTest {
        val dao = database.achievementAdmissionDao()
        database.gameDao().upsertAll(listOf(Game(1, "One", "", 0, 0, 0), Game(2, "Two", "", 0, 0, 0)))
        dao.insertAll(listOf(
            AchievementAdmissionWork("account-a", 4, 1, 1),
            AchievementAdmissionWork("account-a", 4, 2, 2),
            AchievementAdmissionWork("account-a", 5, 2, 2),
        ))

        scheduler.enqueue("account-a", 4)
        scheduler.enqueue("account-a", 4, delayMillis = 120_000)
        scheduler.enqueue("account-a", 5)

        val generationFour = workInfos(WorkManagerAchievementAdmissionScheduler.workName("account-a", 4))
        assertEquals(1, generationFour.size)
        assertEquals(1, generationFour.count { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(generationFour.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
        val timer = workInfos(WorkManagerAchievementAdmissionScheduler.wakeWorkName("account-a", 4))
        assertEquals(1, timer.size)
        assertEquals(WorkInfo.State.ENQUEUED, timer.single().state)
        assertTrue(timer.single().constraints.requiredNetworkType == NetworkType.NOT_REQUIRED)
        assertTrue(workInfos(WorkManagerAchievementAdmissionScheduler.workName("account-a", 5)).isNotEmpty())
    }

    @Test
    fun startupRecoverySchedulesEachPendingIdentity() = runTest {
        database.gameDao().upsertAll(listOf(Game(1, "One", "", 0, 0, 0), Game(2, "Two", "", 0, 0, 0)))
        database.achievementAdmissionDao().insertAll(listOf(
            AchievementAdmissionWork("account-a", 4, 1, 1),
            AchievementAdmissionWork("account-a", 4, 2, 2),
            AchievementAdmissionWork("account-b", 8, 2, 3),
        ))

        scheduler.recoverPending()

        assertEquals(1, workInfos(WorkManagerAchievementAdmissionScheduler.workName("account-a", 4)).size)
        assertEquals(1, workInfos(WorkManagerAchievementAdmissionScheduler.workName("account-b", 8)).size)
    }

    @Test
    fun repeatedRecoveryDoesNotAppendAnotherActiveDrain() = runTest {
        database.gameDao().upsert(Game(1, "One", "", 0, 0, 0))
        database.achievementAdmissionDao().insert(AchievementAdmissionWork("account-a", 4, 1, 1))
        val name = WorkManagerAchievementAdmissionScheduler.workName("account-a", 4)

        scheduler.recoverPending()
        scheduler.recoverPending()

        assertEquals(1, workInfos(name).count { !it.state.isFinished })
    }

    private suspend fun workInfos(name: String) = workManager.getWorkInfosForUniqueWorkFlow(name).first()
}
