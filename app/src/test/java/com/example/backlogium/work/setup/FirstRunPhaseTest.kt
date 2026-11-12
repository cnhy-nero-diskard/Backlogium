package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.DataStoreSetupStateStore
import com.example.backlogium.data.setup.restoredFirstRunPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FirstRunPhaseTest {
    @Test fun legacyActiveSetupMigratesWithoutEnrollingExistingConfiguredUsers() {
        assertNull(restoredFirstRunPhase(null, false))
        assertNull(restoredFirstRunPhase("unknown", false))
        assertEquals(FirstRunPhase.SETUP, restoredFirstRunPhase(null, true))
        assertEquals(FirstRunPhase.HISTORY, restoredFirstRunPhase("HISTORY", true))
    }

    @Test fun phaseAndSkipSurviveARecreatedStoreAndBackgroundCompletionCannotReopenIt() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val original = DataStoreSetupStateStore(context)
        original.setFirstRunSetupActive(true)
        original.advanceToHistory()
        val recreated = DataStoreSetupStateStore(context)
        assertEquals(FirstRunPhase.HISTORY, recreated.firstRunPhaseFlow.first())
        assertTrue(recreated.firstRunSetupActiveFlow.first())
        recreated.setFirstRunSetupActive(false)
        // A detached setup completion arriving after Skip must not recreate a takeover.
        original.advanceToHistory()
        assertNull(DataStoreSetupStateStore(context).firstRunPhaseFlow.first())
        assertFalse(original.firstRunSetupActiveFlow.first())
    }
}
