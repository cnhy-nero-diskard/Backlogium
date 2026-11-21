package com.example.backlogium.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Exact destination routes keep pushed screens and graph routes outside the tab bar. */
internal fun topLevelDestination(route: String?): Destination? =
    Destination.entries.firstOrNull { it.route == route }

@Composable
internal fun AppBottomNavigation(
    currentRoute: String?,
    onDestinationSelected: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = topLevelDestination(currentRoute) ?: return
    // Compose the bar with the route so its measured height and the content viewport change
    // together. A slide-out keeps Scaffold's old bottom padding until the exit finishes.
    NavigationBar(modifier = modifier) {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(destination.icon, contentDescription = destination.label)
                },
                label = { Text(destination.label) },
            )
        }
    }
}
