package com.example.backlogium.data.repo

import com.example.backlogium.test.SettingsDataStoreRule
import org.junit.Rule

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LibrarySortPreferencesTest {
    @get:Rule val settingsFixture = SettingsDataStoreRule()

    @Test fun addedRecentlySortAndDirectionPersistIndependentlyForBothSections() = runTest {
        val dataStore = settingsFixture.create()
        val repository = DataStoreSettingsRepository(dataStore)
        assertEquals(LibrarySortPrefs(), repository.librarySort.first())
        try {
            repository.setFocusSort(LibrarySortKey.ADDED_RECENTLY)
            assertEquals(LibrarySortDirection.DESCENDING, repository.librarySort.first().focusDirection)
            repository.setFocusSortDirection(LibrarySortDirection.ASCENDING)
            assertEquals(LibrarySortKey.PLAYTIME, repository.librarySort.first().library)

            repository.setLibrarySort(LibrarySortKey.ADDED_RECENTLY)
            repository.setLibrarySortDirection(LibrarySortDirection.DESCENDING)
            val reattached = DataStoreSettingsRepository(dataStore).librarySort.first()
            assertEquals(LibrarySortKey.ADDED_RECENTLY, reattached.focus)
            assertEquals(LibrarySortKey.ADDED_RECENTLY, reattached.library)
            assertEquals(LibrarySortDirection.ASCENDING, reattached.focusDirection)
            assertEquals(LibrarySortDirection.DESCENDING, reattached.libraryDirection)
        } finally {
            repository.setFocusSort(LibrarySortKey.NAME)
            repository.setFocusSortDirection(LibrarySortDirection.ASCENDING)
            repository.setLibrarySort(LibrarySortKey.PLAYTIME)
            repository.setLibrarySortDirection(LibrarySortDirection.DESCENDING)
        }
    }
}
