package com.example.backlogium.ui.navigation

import androidx.navigation.NavController
import com.example.backlogium.ui.settings.SettingsRoutes
import com.example.backlogium.ui.settingsGraphBackStackEntryOrNull

/** Navigate between top-level destinations while preserving each destination's saved state. */
internal fun NavController.navigateToTopLevelDestination(route: String) {
    val settingsGraphIsActive = settingsGraphBackStackEntryOrNull() != null

    navigate(route) {
        if (settingsGraphIsActive) {
            popUpTo(SettingsRoutes.GRAPH) { inclusive = true }
        } else {
            popUpTo(Destination.HOME.route) { saveState = true }
        }
        launchSingleTop = true
        restoreState = true
    }
}

/** Return to the active Settings overview without discarding its graph-scoped state. */
internal fun NavController.navigateToSettingsTab() {
    if (settingsGraphBackStackEntryOrNull() != null) {
        navigate(SettingsRoutes.OVERVIEW) {
            popUpTo(SettingsRoutes.OVERVIEW) { inclusive = false }
            launchSingleTop = true
        }
    } else {
        navigateToTopLevelDestination(Destination.SETTINGS.route)
    }
}
