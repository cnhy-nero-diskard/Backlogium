package com.example.backlogium.work.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.backlogium.data.setup.DataStoreSetupStateStore
import com.example.backlogium.data.setup.SetupJournal
import com.example.backlogium.data.setup.SetupOwner
import com.example.backlogium.data.setup.restoredFirstRunPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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

    @Test fun upgradeKeepsLegacyOutcomeAndExactActiveWorkAssociation() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val legacyScope = CoroutineScope(Dispatchers.IO + Job())
        val legacy = PreferenceDataStoreFactory.create(scope = legacyScope) {
            context.preferencesDataStoreFile("setup")
        }
        try {
            legacy.edit { prefs ->
                prefs[stringPreferencesKey("stage_outcome_art")] = "ok"
                prefs[stringPreferencesKey("stage_outcome_sync")] = "never"
                prefs[stringPreferencesKey("stage_outcome_times")] = "never"
                prefs[stringPreferencesKey("active_stage_id")] = "sync"
                prefs[stringPreferencesKey("active_work_id")] = "saved-work-id"
                prefs[stringSetPreferencesKey("active_stage_selection")] = setOf("sync", "times")
            }
        } finally {
            legacyScope.coroutineContext[Job]!!.cancelAndJoin()
        }

        val upgraded = DataStoreSetupStateStore(context).update(fixtureSetupOwner) { it }
        assertEquals("saved-work-id", upgraded.stages.getValue("sync").workId)
        assertTrue(upgraded.stages.getValue("sync").pending)
        assertEquals(SetupOutcome.Succeeded, upgraded.stages.getValue("art").outcome)
        assertEquals(listOf("times"), upgraded.remaining)
    }

    @Test fun upgradeIgnoresUnownedLegacyStateForLaterAccountGeneration() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val legacyScope = CoroutineScope(Dispatchers.IO + Job())
        val legacy = PreferenceDataStoreFactory.create(scope = legacyScope) {
            context.preferencesDataStoreFile("setup")
        }
        try {
            legacy.edit { prefs ->
                prefs[stringPreferencesKey("stage_outcome_art")] = "ok"
                prefs[booleanPreferencesKey("stage_opt_in_sync")] = true
                prefs[stringPreferencesKey("active_stage_id")] = "sync"
                prefs[stringPreferencesKey("active_work_id")] = "account-a-work-id"
                prefs[stringSetPreferencesKey("active_stage_selection")] = setOf("sync", "times")
            }
        } finally {
            legacyScope.coroutineContext[Job]!!.cancelAndJoin()
        }

        val ownerB = SetupOwner("account-b", generation = 1)
        val upgraded = DataStoreSetupStateStore(context).update(ownerB) { it }

        assertEquals(SetupJournal(ownerB), upgraded)
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
