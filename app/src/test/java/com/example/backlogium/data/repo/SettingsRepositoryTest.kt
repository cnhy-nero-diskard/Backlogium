package com.example.backlogium.data.repo

import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.SmartCollectionId
import com.example.backlogium.domain.SmartCollectionVisibility
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun densityPreferences_defaultToList_andPersistIndependently() = runTest {
        val dataStore = SettingsDataStore(RuntimeEnvironment.getApplication())
        val repository = DataStoreSettingsRepository(dataStore)

        assertEquals(GameListDensity.LIST, repository.libraryDensity.first())
        assertEquals(GameListDensity.LIST, repository.collectionDensity.first())

        repository.setLibraryDensity(GameListDensity.GRID)
        repository.setCollectionDensity(GameListDensity.COMPACT_GRID)

        assertEquals(GameListDensity.GRID, repository.libraryDensity.first())
        assertEquals(GameListDensity.COMPACT_GRID, repository.collectionDensity.first())
    }

    @Test
    fun clearSharedGameAnnouncement_reachesDataStore_throughTheProductionRepository() = runTest {
        // Regression: DataStoreSettingsRepository previously left this call on the interface's
        // no-op default, so dismissal from the production repository never reached DataStore.
        val dataStore = SettingsDataStore(RuntimeEnvironment.getApplication())
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)
        dataStore.setSharedGameAnnouncement(appId = 10L, name = "Game A", announcedAt = 1_000L)

        repository.clearSharedGameAnnouncement(10L)

        assertEquals(null, repository.sharedGameAnnouncement.first())
    }

    @Test
    fun sharedGameAnnouncement_queuesInsteadOfOverwriting() = runTest {
        // Regression: a single mutable slot let a second admission silently overwrite the first
        // game's cue before it was ever seen, which is exactly what "must never be silent" rules
        // out.
        val dataStore = SettingsDataStore(RuntimeEnvironment.getApplication())
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        dataStore.setSharedGameAnnouncement(appId = 10L, name = "Game A", announcedAt = 1_000L)
        dataStore.setSharedGameAnnouncement(appId = 20L, name = "Game B", announcedAt = 2_000L)

        assertEquals(10L, repository.sharedGameAnnouncement.first()?.appId)

        repository.clearSharedGameAnnouncement(10L)

        assertEquals(20L, repository.sharedGameAnnouncement.first()?.appId)

        repository.clearSharedGameAnnouncement(20L)

        assertEquals(null, repository.sharedGameAnnouncement.first())
    }

    @Test
    fun sessionEndIsDurableAndClearsLiveSessionInTheSameRepositoryOperation() = runTest {
        val dataStore = SettingsDataStore(RuntimeEnvironment.getApplication())
        val repository = DataStoreSettingsRepository(dataStore)
        val sessionEnd = PlaySessionEnd(appId = 10L, endedAt = 2_000L, steamId = "account-a")

        repository.setLiveSession(appId = 10L, startedAt = 1_000L)
        repository.recordSessionEnd(sessionEnd, LiveSessionState())

        assertEquals(LiveSessionState(), repository.liveSession.first())
        assertEquals(listOf(sessionEnd), repository.pendingSessionEnds.first())

        repository.acknowledgeSessionEnd(sessionEnd)

        assertTrue(repository.pendingSessionEnds.first().isEmpty())
    }

    @Test
    fun smartCollectionVisibility_defaultsVisible_andPersistsAcrossRepositoryInstances() = runTest {
        val dataStore = SettingsDataStore(RuntimeEnvironment.getApplication())
        val repository = DataStoreSettingsRepository(dataStore)

        assertTrue(repository.smartCollectionVisibility.first().isVisible(
            com.example.backlogium.domain.SmartCollectionId.DROPPED,
        ))

        repository.setSmartCollectionVisibility(
            com.example.backlogium.domain.SmartCollectionVisibility(
                hidden = setOf(com.example.backlogium.domain.SmartCollectionId.DROPPED),
            ),
        )

        val reopenedRepository = DataStoreSettingsRepository(
            SettingsDataStore(RuntimeEnvironment.getApplication()),
        )
        assertFalse(reopenedRepository.smartCollectionVisibility.first().isVisible(
            com.example.backlogium.domain.SmartCollectionId.DROPPED,
        ))
        assertTrue(reopenedRepository.smartCollectionVisibility.first().isVisible(
            com.example.backlogium.domain.SmartCollectionId.QUICK_WINS,
        ))
    }

    @Test
    fun smartCollectionVisible_concurrentTogglesOfDifferentLists_bothSurvive() = runTest {
        // Regression: toggling used to read the shared hidden-id set and then write a whole
        // replacement, so two near-concurrent switches from the manage dialog could both read
        // the same old set and the slower write would silently discard the other toggle.
        val dataStore = SettingsDataStore(RuntimeEnvironment.getApplication())
        val repository: SettingsRepository = DataStoreSettingsRepository(dataStore)

        try {
            coroutineScope {
                launch { repository.setSmartCollectionVisible(SmartCollectionId.DROPPED, visible = false) }
                launch { repository.setSmartCollectionVisible(SmartCollectionId.QUICK_WINS, visible = false) }
            }

            val visibility = repository.smartCollectionVisibility.first()
            assertFalse(visibility.isVisible(SmartCollectionId.DROPPED))
            assertFalse(visibility.isVisible(SmartCollectionId.QUICK_WINS))
        } finally {
            // The DataStore delegate is a process-wide singleton, so the hidden ids would
            // otherwise leak into tests that assert the all-visible default.
            repository.setSmartCollectionVisibility(SmartCollectionVisibility())
        }
    }
}
