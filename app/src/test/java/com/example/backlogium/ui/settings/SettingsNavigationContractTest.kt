package com.example.backlogium.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationContractTest {
    @Test
    fun overviewAndDetailRoutesAreStableAndEachGroupHasOneDestination() {
        assertEquals("settings", SettingsRoutes.OVERVIEW)
        assertEquals(
            setOf(
                "settings/account-sync",
                "settings/gameplay",
                "settings/data-privacy",
                "settings/advanced",
            ),
            SettingsRoutes.detailRoutes,
        )
        assertEquals(SettingsGroup.entries.map { it.route }.toSet(), SettingsRoutes.detailRoutes)
    }

    @Test
    fun everyDetailRouteReturnsToOverviewForToolbarAndSystemBack() {
        SettingsGroup.entries.forEach { group ->
            assertTrue(group.route.startsWith("${SettingsRoutes.OVERVIEW}/"))
            assertEquals(SettingsRoutes.OVERVIEW, settingsBackStackTarget(group.route))
        }
    }

    @Test
    fun leavingTheSettingsGraphUsesTheTopLevelDestinationOnly() {
        assertEquals(SettingsRoutes.OVERVIEW, settingsBackStackTarget(SettingsRoutes.OVERVIEW))
        assertEquals(null, settingsBackStackTarget("library"))
    }
}
