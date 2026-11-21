package com.example.backlogium.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection keeps its screen lifetime; discovery context now follows LibraryVisitStateTest.
 */
class LibraryTransientResetTest {

    @Test
    fun navigationAwayResetsSelection() {
        assertTrue(shouldClearLibrarySelection(isChangingConfigurations = false))
    }

    @Test
    fun recreationRetainsSelection() {
        assertFalse(shouldClearLibrarySelection(isChangingConfigurations = true))
    }
}
