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
