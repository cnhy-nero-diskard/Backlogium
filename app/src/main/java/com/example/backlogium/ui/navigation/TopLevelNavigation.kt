package com.example.backlogium.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import com.example.backlogium.ui.settings.SettingsRoutes

/** Navigate between top-level destinations while preserving each destination's saved state. */
internal fun NavController.navigateToTopLevelDestination(route: String) {
    val inSettingsGraph = currentDestination?.hierarchy?.any {
        it.route == SettingsRoutes.GRAPH
    } == true

    navigate(route) {
        if (inSettingsGraph) {
            popUpTo(SettingsRoutes.GRAPH) { inclusive = true }
        } else {
            popUpTo(Destination.HOME.route) { saveState = true }
        }
        launchSingleTop = true
        restoreState = true
    }
}
