package com.example.backlogium.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.backlogium.ui.diagnostics.DiagnosticsScreen

/** Route for the HLTB match-review surface — a sub-destination reached from the Library. */
private const val ROUTE_HLTB_REVIEW = "hltb_review"

/** Route for the credentials onboarding flow — reached from the Settings account section. */
private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_DIAGNOSTICS = "diagnostics"

/** Route for the per-game detail screen — a sub-destination reached from the Library. */
private const val ROUTE_GAME_DETAIL = "game_detail/{appId}"
private fun gameDetailRoute(appId: Long) = "game_detail/$appId"

/** App shell: bottom navigation between Home, Library, History, and Settings. */
@Composable
fun BacklogiumAppRoot() {
    val navController = rememberNavController()
    val destinations = Destination.entries
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // Game detail isn't one of the top-level tabs, so the bottom bar would show with nothing
    // selected — hide it there instead of leaving a misleading state.
    val onGameDetail = currentDestination?.route == ROUTE_GAME_DETAIL

    // Hoisted above the Scaffold so a screen-reported wash can paint behind the top bar too, not
    // just its own content area — the game detail screen's header-art wash, and Home's now-playing
    // tint, which is what lets the profile header and the now-playing panel read as one block.
    // Cleared when the screen is neither of those, so a color never lingers into another tab or
    // flashes stale on the next game opened. (Home clears its own on leaving composition too, but
    // game detail reports once on load and never retracts it.)
    var accentColor by remember { mutableStateOf<Color?>(null) }
    val onHome = currentDestination?.route == Destination.HOME.route
    LaunchedEffect(onGameDetail, onHome) {
        if (!onGameDetail && !onHome) accentColor = null
    }

    // Shell-level and once per install: the ongoing now-playing notification is otherwise silently
    // dead on a fresh install, and no screen is a natural owner of an app-wide permission.
    NotificationPermissionRequest()

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenBackdrop(accentColor = accentColor)
        Scaffold(
            containerColor = Color.Transparent,
            // Spelled out for the same reason as ProfileHeader's Surface: Scaffold only infers
            // contentColor for known scheme colors, and a transparent container isn't one — left
            // implicit, every screen's text/icons that rely on the inherited color (not just game
            // detail's) would silently fall back to plain black.
            contentColor = MaterialTheme.colorScheme.onBackground,
            // Shell-level, so the identity strip survives navigation without each screen
            // re-declaring it. `innerPadding` below already offsets the NavHost for it.
            // Transparent on Home *unconditionally*, and on any screen painting a backdrop wash.
            //
            // The unconditional part matters: with its own `surface` fill the header ended at a
            // hard color step against Home's now-playing panel (whose top edge is transparent),
            // and that step read as a crease. Dropping the fill puts header and panel on the same
            // pixels — the shell backdrop — so the boundary cannot draw a line whether or not the
            // in-game wash happens to be active.
            topBar = { ProfileHeader(transparent = onHome || accentColor != null) },
            bottomBar = {
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
                composable(Destination.HOME.route) {
                    HomeScreen(onAccentColorChanged = { accentColor = it })
                }
                composable(Destination.LIBRARY.route) {
                    LibraryScreen(
                        onOpenReview = { navController.navigate(ROUTE_HLTB_REVIEW) },
                        onOpenGameDetail = { appId -> navController.navigate(gameDetailRoute(appId)) },
                    )
                }
                composable(Destination.HISTORY.route) { HistoryScreen() }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen(
                        onEditCredentials = { navController.navigate(ROUTE_ONBOARDING) },
                        onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                    )
                }
                composable(ROUTE_DIAGNOSTICS) { DiagnosticsScreen() }
                composable(ROUTE_ONBOARDING) {
                    OnboardingScreen(onCompleted = { navController.popBackStack() })
                }
                composable(ROUTE_HLTB_REVIEW) { HltbReviewScreen() }
                composable(
                    route = ROUTE_GAME_DETAIL,
                    arguments = listOf(navArgument("appId") { type = NavType.LongType }),
                ) { GameDetailScreen(onAccentColorChanged = { accentColor = it }) }
            }
        }
    }
}

/**
 * The shell's own background, painted once behind the Scaffold (which is transparent) so a
 * screen-reported [accentColor] — the game detail screen's header-art wash — can span edge to
 * edge, behind the top bar included, instead of being boxed into that screen's own content area.
 * Every other screen simply gets the flat theme background, unchanged from before this existed.
 */
@Composable
private fun ScreenBackdrop(accentColor: Color?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (accentColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to accentColor.copy(alpha = 0.75f),
                                0.45f to accentColor.copy(alpha = 0.32f),
                                1f to Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
    }
}
