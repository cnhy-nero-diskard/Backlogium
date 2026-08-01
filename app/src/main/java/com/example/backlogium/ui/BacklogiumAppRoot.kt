package com.example.backlogium.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.backlogium.ui.components.ProfileHeader
import com.example.backlogium.ui.gamedetail.GameDetailScreen
import com.example.backlogium.ui.history.HistoryScreen
import com.example.backlogium.ui.home.HomeScreen
import com.example.backlogium.ui.library.LibraryScreen
import com.example.backlogium.ui.navigation.Destination
import com.example.backlogium.ui.onboarding.OnboardingScreen
import com.example.backlogium.ui.review.HltbReviewScreen
import com.example.backlogium.ui.settings.SettingsScreen

/** Route for the HLTB match-review surface — a sub-destination reached from the Library. */
private const val ROUTE_HLTB_REVIEW = "hltb_review"

/** Route for the credentials onboarding flow — reached from the Settings account section. */
private const val ROUTE_ONBOARDING = "onboarding"

/** Route for the per-game detail screen — a sub-destination reached from the Library. */
private const val ROUTE_GAME_DETAIL = "game_detail/{appId}"
private fun gameDetailRoute(appId: Long) = "game_detail/$appId"

/** App shell: bottom navigation between Home, Library, History, and Settings. */
@Composable
fun BacklogiumAppRoot() {
    val navController = rememberNavController()
    val destinations = Destination.entries

    Scaffold(
        // Shell-level, so the identity strip survives navigation without each screen
        // re-declaring it. `innerPadding` below already offsets the NavHost for it.
        topBar = { ProfileHeader() },
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            // Game detail isn't one of the top-level tabs, so the bar would show with
            // nothing selected — hide it there instead of leaving a misleading state.
            val onGameDetail = currentDestination?.route == ROUTE_GAME_DETAIL
            AnimatedVisibility(
                visible = !onGameDetail,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
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
                LibraryScreen(
                    onOpenReview = { navController.navigate(ROUTE_HLTB_REVIEW) },
                    onOpenGameDetail = { appId -> navController.navigate(gameDetailRoute(appId)) },
                )
            }
            composable(Destination.HISTORY.route) { HistoryScreen() }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(onEditCredentials = { navController.navigate(ROUTE_ONBOARDING) })
            }
            composable(ROUTE_ONBOARDING) {
                OnboardingScreen(onCompleted = { navController.popBackStack() })
            }
            composable(ROUTE_HLTB_REVIEW) { HltbReviewScreen() }
            composable(
                route = ROUTE_GAME_DETAIL,
                arguments = listOf(navArgument("appId") { type = NavType.LongType }),
            ) { GameDetailScreen() }
        }
    }
}
