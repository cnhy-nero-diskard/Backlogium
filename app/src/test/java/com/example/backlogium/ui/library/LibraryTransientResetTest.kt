package com.example.backlogium.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the reset-on-leave contract for Library's transient ViewModel state (selection and
 * filters): leaving Library resets it, while a configuration/activity recreation (rotation,
 * locale/theme change) must not — the Hilt ViewModel survives recreation, so clearing on every
 * composition disposal would wipe filters the user never left.
 */
class LibraryTransientResetTest {

    @Test
    fun navigationAwayResetsTransientState() {
        assertTrue(shouldClearLibraryTransientState(isChangingConfigurations = false))
    }

    @Test
    fun recreationRetainsTransientState() {
        assertFalse(shouldClearLibraryTransientState(isChangingConfigurations = true))
    }
}
