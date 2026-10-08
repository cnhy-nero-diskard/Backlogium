package com.example.backlogium.ui.library

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavController
import com.example.backlogium.ui.navigation.Destination
import com.example.backlogium.ui.settings.SettingsRoutes

/** Only an active Library entry can own a pushed screen; saved inactive tabs do not count. */
internal fun isLibraryFlow(route: String?, libraryEntryPresent: Boolean): Boolean = when {
    route == null -> false
    Destination.entries.any { it.route == route } -> route == Destination.LIBRARY.route
    route == SettingsRoutes.GRAPH -> false
    else -> libraryEntryPresent
}

@Composable
internal fun LibraryVisitEffects(
    navController: NavController,
    visit: LibraryVisitState,
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    DisposableEffect(navController, visit) {
        // Destination listeners run during navigation, before the new screen reads visit data.
        val listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
            val libraryEntryPresent = runCatching {
                controller.getBackStackEntry(Destination.LIBRARY.route)
            }.isSuccess
            visit.routeChanged(isLibraryFlow(destination.route, libraryEntryPresent), elapsedRealtime())
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    DisposableEffect(visit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> visit.background(elapsedRealtime())
                Lifecycle.Event.ON_START -> visit.foreground(elapsedRealtime())
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
