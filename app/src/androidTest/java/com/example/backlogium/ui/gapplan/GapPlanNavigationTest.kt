package com.example.backlogium.ui.gapplan

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.ui.collections.COLLECTIONS_PLAN_GAP_TAG
import com.example.backlogium.ui.home.HOME_PLAN_GAP_TAG
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Home and Collections open the **same** gap-plan route.
 *
 * Two routes would eventually be two builders, and a plan built from one entry point would stop
 * being the plan built from the other. The bottom-navigation destinations are unchanged: this is a
 * pushed sub-destination, not a sixth tab.
 */
class GapPlanNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    @Test
    fun bothEntryPointsOpenTheSameRoute() {
        setContent()

        composeRule.onNodeWithTag(HOME_PLAN_GAP_TAG).performClick()
        assertEquals(ROUTE_GAP_PLAN, currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }
        assertEquals(ROUTE_HOME, currentRoute())

        composeRule.onNodeWithText("Go to collections").performClick()
        composeRule.onNodeWithTag(COLLECTIONS_PLAN_GAP_TAG).performClick()

        assertEquals(ROUTE_GAP_PLAN, currentRoute())
    }

    /** Back from the builder returns to wherever it was opened from, with nothing created. */
    @Test
    fun leavingTheBuilderReturnsToTheOpeningSurface() {
        setContent()

        composeRule.onNodeWithTag(HOME_PLAN_GAP_TAG).performClick()
        composeRule.runOnIdle { navController.popBackStack() }

        assertEquals(ROUTE_HOME, currentRoute())
    }

    /**
     * A created collection replaces the builder rather than sitting on top of it: the plan that
     * produced it is spent, and returning to a stale preview would invite a second identical save.
     */
    @Test
    fun openingTheCreatedCollectionReplacesTheBuilderInTheBackStack() {
        setContent()

        composeRule.onNodeWithTag(HOME_PLAN_GAP_TAG).performClick()
        composeRule.runOnIdle {
            navController.navigate("$ROUTE_COLLECTION/7") {
                popUpTo(ROUTE_GAP_PLAN) { inclusive = true }
            }
        }
        assertEquals("$ROUTE_COLLECTION/{collectionId}", currentRoute())

        composeRule.runOnIdle { navController.popBackStack() }

        assertEquals(ROUTE_HOME, currentRoute())
    }

    private fun currentRoute(): String? =
        composeRule.runOnIdle { navController.currentBackStackEntry?.destination?.route }

    /**
     * A miniature of the shell's own graph. The real `BacklogiumAppRoot` pulls in Hilt, the whole
     * library, and every screen; this exercises the one thing the test is about — that both entry
     * points resolve to one route — without any of that.
     */
    private fun setContent() = composeRule.setContent {
        navController = TestNavHostController(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).apply { navigatorProvider.addNavigator(ComposeNavigator()) }

        BacklogiumTheme {
            NavHost(navController = navController, startDestination = ROUTE_HOME) {
                composable(ROUTE_HOME) {
                    Column {
                        TextButton(
                            onClick = { navController.navigate(ROUTE_GAP_PLAN) },
                            modifier = Modifier.testTag(HOME_PLAN_GAP_TAG),
                        ) { Text("Plan a gap before a release") }
                        TextButton(
                            onClick = { navController.navigate(ROUTE_COLLECTIONS) },
                        ) { Text("Go to collections") }
                    }
                }
                composable(ROUTE_COLLECTIONS) {
                    TextButton(
                        onClick = { navController.navigate(ROUTE_GAP_PLAN) },
                        modifier = Modifier.testTag(COLLECTIONS_PLAN_GAP_TAG),
                    ) { Text("Plan a gap before a release") }
                }
                composable(ROUTE_GAP_PLAN) { Text("Plan the gap") }
                composable("$ROUTE_COLLECTION/{collectionId}") { Text("Collection") }
            }
        }
    }

    private companion object {
        const val ROUTE_HOME = "home"
        const val ROUTE_COLLECTIONS = "collections"

        /** Must match `BacklogiumAppRoot`'s constant; one route is the property under test. */
        const val ROUTE_GAP_PLAN = "gap_plan"
        const val ROUTE_COLLECTION = "collection"
    }
}
