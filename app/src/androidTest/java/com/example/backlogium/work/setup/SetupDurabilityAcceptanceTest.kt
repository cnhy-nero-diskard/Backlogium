package com.example.backlogium.work.setup

import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.backlogium.BacklogiumApp
import com.example.backlogium.work.SteamSyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in acceptance against the debug app on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class SetupDurabilityAcceptanceTest {
    @Test
    fun recordConsentForARealProcessRestart() = runBlocking<Unit> {
        assumeScenario("prepare-consent")
        val app = app()
        val profile = app.playerProfileDao.get()
        check(profile?.playtimeBackfilled == false) { "Use a fresh, synced test installation" }
        val setup = app.setupRecovery.get()
        setup.claimFirstRunSetup()
        setup.advanceToHistory()
        val request = checkNotNull(app.historyImportRecovery.get().request())
        assertEquals(request.id, app.playerProfileDao.get()?.historyImportRequestId)
        assertFalse(app.playerProfileDao.get()!!.playtimeBackfilled)
        Log.i(TAG, "history=consent-recorded request=${request.id} pid=${Process.myPid()}")
    }

    @Test
    fun replacementAccountCannotInheritQueuedSetupOrFirstRunState() = runBlocking<Unit> {
        assumeScenario("replace-account")
        val app = app()
        check(Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
            "Use an offline disposable emulator for the account-reset check"
        }
        val setup = app.setupRecovery.get()
        val manager = WorkManager.getInstance(app)
        withTimeout(20_000) {
            setup.state.first { it.loaded && !it.running }
            setup.claimFirstRunSetup()
            val oldOwner = checkNotNull(setup.firstRunOwner.first())
            setup.start(setOf(SetupStageRegistry.STAGE_LIBRARY_SYNC), recordUnselectedAsSkipped = false)
            setup.state.first { it.finished && SetupStageRegistry.STAGE_LIBRARY_SYNC in it.pending }
            val originalWork = manager.getWorkInfosForUniqueWorkFlow(SteamSyncWorker.ONE_TIME_NAME)
                .first { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }
                .single { it.state == WorkInfo.State.ENQUEUED }

            app.accountChangeCoordinator.apply("fixture-key", "76561198000000001")
            setup.state.first {
                it.loaded && it.pending.isEmpty() && SetupStageRegistry.STAGE_LIBRARY_SYNC in it.outcomes
            }
            assertNull(setup.firstRunPhase.first())
            assertEquals(SetupOutcome.NeverRun, setup.state.value.outcomes[SetupStageRegistry.STAGE_LIBRARY_SYNC])
            assertFalse(app.playerProfileDao.get()!!.playtimeBackfilled)
            assertNull(app.playerProfileDao.get()!!.historyImportRequestId)
            assertTrue(runCatching { app.historyImportRecovery.get().request() }.isFailure)
            assertNotNull(manager.getWorkInfoByIdFlow(originalWork.id).first())

            setup.claimFirstRunSetup()
            setup.advanceToHistory()
            setup.releaseFirstRunSetup(oldOwner)
            assertEquals(FirstRunPhase.HISTORY, setup.firstRunPhase.first())
            Log.i(TAG, "account=replaced queued-old-work=${originalWork.id} replacement-baseline=unconfirmed")
        }
    }

    private fun assumeScenario(scenario: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("setupDeviceScenario") == scenario)
    }

    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BacklogiumApp

    private companion object {
        const val TAG = "SetupRecoveryTest"
    }
}
