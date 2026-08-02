package com.example.backlogium.data.repo

import com.example.backlogium.data.local.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    @Test
    fun liveMonitorEnabled_defaultsOff_andRepositoryForwardsItsWrite() = runTest {
        val dataStore = SettingsDataStore(RuntimeEnvironment.getApplication())
        val repository = DataStoreSettingsRepository(dataStore)

        assertFalse(dataStore.liveMonitorEnabledFlow.first())
        assertFalse(repository.liveMonitorEnabled.first())

        repository.setLiveMonitorEnabled(true)

        assertTrue(dataStore.liveMonitorEnabledFlow.first())
        assertTrue(repository.liveMonitorEnabled.first())
    }
}
