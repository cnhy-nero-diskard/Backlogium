package com.example.backlogium.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavController
import com.example.backlogium.ui.navigation.Destination
import com.example.backlogium.ui.settings.SettingsRoutes

/** The last top-level entry identifies a pushed route's origin, including restored stacks. */
internal fun isLibraryFlow(routes: List<String>): Boolean = routes.lastOrNull { route ->
    Destination.entries.any { it.route == route } || route == SettingsRoutes.GRAPH
} == Destination.LIBRARY.route

@Composable
internal fun LibraryVisitEffects(navController: NavController, visit: LibraryVisitState) {
    DisposableEffect(navController, visit) {
        // Destination listeners run during navigation, before the new screen reads visit data.
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            visit.routeChanged(isLibraryFlow(controller.currentBackStack.value.mapNotNull {
                it.destination.route
            }))
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    DisposableEffect(visit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> visit.background()
                Lifecycle.Event.ON_START -> visit.foreground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
