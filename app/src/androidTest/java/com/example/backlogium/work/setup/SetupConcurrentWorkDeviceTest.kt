package com.example.backlogium.work.setup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Real scheduler exercise with fake responses: never contacts Steam or uses stored credentials. */
@RunWith(AndroidJUnit4::class)
class SetupConcurrentWorkDeviceTest {
    @Test
    fun interruptedHistoryImportRecoversWithoutCreditingTwice() = runBlocking {
        withTimeout(20_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = androidx.room.Room.inMemoryDatabaseBuilder(context,
                com.example.backlogium.data.local.BacklogiumDatabase::class.java).build()
            try {
                val account = "76561198000000001"
                db.playerProfileDao().upsert(com.example.backlogium.data.local.entity.PlayerProfile(
                    steamId = account, lastSyncAt = 1L))
                db.gameDao().upsertAll(listOf(com.example.backlogium.data.local.entity.Game(
                    appId = 10L, name = "Fixture game", iconUrl = "", playtimeForever = 120,
                    playtime2Weeks = 0, lastPlaytime = 120)))
                val updater = com.example.backlogium.domain.GamificationUpdater(
                    db.sessionDao(), db.dailyProgressDao(), db.playerProfileDao(), db.hltbDataDao(),
                    db.achievementDao(), db.gameDao(), db.hiddenGameDao())
                val transaction = com.example.backlogium.data.backup.RoomDatabaseTransactionScope(db)
                fun importer(boundary: com.example.backlogium.data.backup.DatabaseTransactionScope) =
                    com.example.backlogium.domain.PlaytimeBackfillUseCase(
                        db.gameDao(), db.sessionDao(), db.playerProfileDao(),
                        com.example.backlogium.data.local.SettingsDataStore(context), updater,
                        com.example.backlogium.domain.SystemTimeProvider(),
                        com.example.backlogium.domain.DerivedStateWriteCoordinator(),
                        com.example.backlogium.work.SteamSyncCoordinator(), boundary,
                        object : com.example.backlogium.data.repo.CredentialsProvider {
                            override suspend fun currentCredentials() = com.example.backlogium.data.repo.CredentialsState.Configured("fixture-key", account)
                        }, com.example.backlogium.data.credentials.AccountChangeMarkerStore(context))
                val interrupted = object : com.example.backlogium.data.backup.DatabaseTransactionScope {
                    override suspend fun <R> run(block: suspend () -> R): R {
                        transaction.run(block)
                        error("fixture interruption after capture")
                    }
                }
                org.junit.Assert.assertTrue(runCatching { importer(interrupted)() }.isFailure)
                org.junit.Assert.assertTrue(db.playerProfileDao().get()!!.pendingImportRecompute)
                assertFalse(importer(transaction)())
                val xp = db.playerProfileDao().get()!!.totalXp
                org.junit.Assert.assertTrue(xp > 0)
                assertFalse(db.playerProfileDao().get()!!.pendingImportRecompute)
                assertFalse(importer(transaction)())
                assertEquals(xp, db.playerProfileDao().get()!!.totalXp)
                assertEquals(120, db.gameDao().getById(10L)!!.backfillMinutes)
                android.util.Log.i("SetupRecoveryTest", "history=interrupted-then-recovered minutes=120 repeated=unchanged")
            } finally { db.close() }
        }
        Unit
    }

    @Test
    fun reuseAndRecoveryDoNotReplaceConcurrentWork() = runBlocking {
        withTimeout(20_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setExecutor(SynchronousExecutor()).build())
            val manager = WorkManager.getInstance(context)
            DeviceSetupResponseWorker.response = CompletableDeferred()
            val prefix = "setup-device-${java.util.UUID.randomUUID()}"
            val periodicName = "$prefix-periodic"
            val manualName = "$prefix-manual"
            val monitorName = "$prefix-monitor"
            try {
                val periodic = PeriodicWorkRequestBuilder<DeviceSetupResponseWorker>(15, TimeUnit.MINUTES).build()
                manager.enqueueUniquePeriodicWork(periodicName, ExistingPeriodicWorkPolicy.KEEP, periodic)
                manager.enqueueUniqueWork(monitorName, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<DeviceSetupResponseWorker>().build())
                val manual = OneTimeWorkRequestBuilder<DeviceSetupResponseWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .build()
                manager.enqueueUniqueWork(manualName, ExistingWorkPolicy.KEEP, manual)
                var admitted: String? = null
                val observed = CompletableDeferred<Unit>()
                val runner = WorkStageRunner(manager, manualName, trigger = {
                    manager.enqueueUniqueWork(manualName, ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<DeviceSetupResponseWorker>().build())
                }, progressOf = { null }, failureReason = "Fake response failed")
                val attempt = async {
                    runner.run(onProgress = { observed.complete(Unit) }, onWorkStarted = { admitted = it })
                }
                observed.await()
                assertEquals(manual.id.toString(), admitted)
                assertFalse(attempt.isCompleted)
                assertEquals(1, manager.getWorkInfosForUniqueWorkFlow(manualName).first().size)
                assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfosForUniqueWorkFlow(periodicName)
                    .first { it.any { info -> info.state == WorkInfo.State.RUNNING } }.single().state)
                assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfosForUniqueWorkFlow(monitorName)
                    .first { it.any { info -> info.state == WorkInfo.State.RUNNING } }.single().state)
                android.util.Log.i("SetupRecoveryTest", "stage=library_sync work=$admitted state=WAITING reused=true replacement=false error=none")
                WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(manual.id)
                manager.getWorkInfosForUniqueWorkFlow(manualName).first { it.single().state == WorkInfo.State.RUNNING }
                DeviceSetupResponseWorker.response.complete(Unit)
                assertEquals(SetupOutcome.Succeeded, attempt.await())
                val recovered = WorkStageRunner(manager, manualName, { error("must not re-enqueue") },
                    progressOf = { null }, failureReason = "Fake response failed")
                assertEquals(SetupOutcome.Succeeded, recovered.recover(manual.id.toString()) {})
                android.util.Log.i("SetupRecoveryTest", "stage=library_sync work=$admitted state=SUCCEEDED recovery=same-id error=none")
                Unit
            } finally {
                listOf(periodicName, manualName, monitorName).forEach { manager.cancelUniqueWork(it).result.get() }
            }
        }
    }
}

class DeviceSetupResponseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { response.await(); return Result.success() }
    companion object { var response = CompletableDeferred<Unit>() }
}
