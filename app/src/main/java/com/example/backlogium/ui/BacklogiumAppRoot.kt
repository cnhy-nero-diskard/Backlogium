package com.example.backlogium.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.backlogium.ui.history.HistoryScreen
import com.example.backlogium.ui.home.HomeScreen
import com.example.backlogium.ui.library.LibraryScreen
import com.example.backlogium.ui.navigation.Destination
import com.example.backlogium.ui.review.HltbReviewScreen

/** Route for the HLTB match-review surface — a sub-destination reached from the Library. */
private const val ROUTE_HLTB_REVIEW = "hltb_review"

/** App shell: bottom navigation between Home, Library, and History. */
@Composable
fun BacklogiumAppRoot() {
    val navController = rememberNavController()
    val destinations = Destination.entries

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                destinations.forEach { destination ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(Destination.HOME.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.HOME.route) { HomeScreen() }
            composable(Destination.LIBRARY.route) {
                LibraryScreen(onOpenReview = { navController.navigate(ROUTE_HLTB_REVIEW) })
            }
            composable(Destination.HISTORY.route) { HistoryScreen() }
            composable(ROUTE_HLTB_REVIEW) { HltbReviewScreen() }
        }
    }
}
