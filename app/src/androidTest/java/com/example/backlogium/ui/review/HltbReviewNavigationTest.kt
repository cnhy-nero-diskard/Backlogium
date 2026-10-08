package com.example.backlogium.ui.review

import android.os.Parcel
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.navigation.navigateToTopLevelDestination
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class ReviewRouteTestViewModel : ViewModel() {
    internal val session = HltbReviewSession()
}

/** Real reviewer presentation and session reducer hosted under a route-scoped ViewModel. */
@RunWith(AndroidJUnit4::class)
class HltbReviewNavigationTest {
    private val restartPhase = InstrumentationRegistry.getArguments().getString("hltbRestartPhase")
    private val restartFile get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "hltb-review-route-test.bin")
    private val queue = mutableStateOf(listOf(game(1, true), game(2), game(3)))
    private lateinit var navigation: NavHostController
    private lateinit var holder: ReviewRouteTestViewModel

    private fun game(id: Long, ambiguous: Boolean = false) = MatchCenterGameUi(
        id, "Steam game $id",
        matchStatus = if (ambiguous) HltbMatchState.NEEDS_REVIEW else HltbMatchState.UNMATCHED,
        candidates = if (ambiguous) listOf(HltbCandidate(id * 10, "HLTB game $id")) else emptyList(),
    )

    @Composable private fun Host() {
        val controller = rememberNavController()
        remember(controller) {
            if (restartPhase == "restore") {
                val parcel = Parcel.obtain()
                try {
                    val bytes = restartFile.readBytes()
                    parcel.unmarshall(bytes, 0, bytes.size)
                    parcel.setDataPosition(0)
                    controller.restoreState(parcel.readBundle(javaClass.classLoader))
                } finally { parcel.recycle() }
            }
            true
        }
        SideEffect { navigation = controller }
        BacklogiumTheme {
            NavHost(controller, startDestination = "home") {
                composable("home") {
                    Column {
                        Button(onClick = { controller.navigate("hltb_review") }) { Text("Open general review") }
                        Button(onClick = { controller.navigate("hltb_review?appId=1") }) { Text("Review game 1") }
                    }
                }
                composable("hltb_review?appId={appId}", arguments = listOf(
                    navArgument("appId") { type = NavType.LongType; defaultValue = -1L },
                )) { entry ->
                    val routeHolder: ReviewRouteTestViewModel = viewModel()
                    val session by routeHolder.session.state.collectAsStateWithLifecycle()
                    val appId = entry.arguments?.getLong("appId")?.takeIf { it >= 0 }
                    SideEffect { holder = routeHolder }
                    LaunchedEffect(appId) { appId?.let(routeHolder.session::selectGame) }
                    LaunchedEffect(queue.value) { routeHolder.session.updateQueue(queue.value) }
                    HltbReviewContent(
                        session.toUiState(), onDone = { controller.popBackStack() },
                        actions = HltbReviewActions(
                            onNext = { routeHolder.session.navigate(1, it) },
                            onPrevious = { routeHolder.session.navigate(-1, it) },
                            onSkip = routeHolder.session::skip,
                            onReviewSkipped = routeHolder.session::reviewSkipped,
                            onResolve = { id, _, _ -> queue.value = queue.value.filterNot { it.appId == id } },
                        ),
                    )
                }
                composable("detail") { Text("Temporary detail") }
                composable("library") { Text("Library tab") }
            }
        }
    }

    init { ScreenshotTestActivity.recreationContent = { Host() } }
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    @After fun cleanup() { ScreenshotTestActivity.recreationContent = null }

    private fun selected(id: Long) {
        compose.onNodeWithText("Steam game $id").assertIsDisplayed()
        compose.runOnIdle { assertEquals(id, holder.session.state.value.selection.persistedAppId) }
    }

    @Test fun nextMatchPartitionChangeAndLastSkipExhaustWithoutWrap() {
        compose.onNodeWithText("Open general review").performClick()
        selected(1)
        compose.onNodeWithText("Next").performClick()
        selected(2)
        compose.runOnIdle { queue.value = listOf(game(1, true), game(2, true), game(3)) }
        selected(2)
        compose.onNodeWithText("Use match").performScrollTo().performClick()
        selected(3)
        compose.onNodeWithText("Skip for this review session").performClick()
        compose.onNodeWithText("Review pass complete").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(1L, 3L), queue.value.map { it.appId })
            assertNull(holder.session.state.value.selection.persistedAppId)
        }
        capture("general-exhausted")
        compose.onNodeWithText("Review skipped").performClick()
        selected(1)
        compose.onNodeWithText("Use match").performScrollTo().performClick()
        selected(3)
    }

    @Test fun scopedDeferralSurvivesRecreationAndDetailButNewRouteResetsIt() {
        compose.onNodeWithText("Review game 1").performClick()
        selected(1)
        compose.onNodeWithText("Skip for this review session").performClick()
        compose.onNodeWithText("Review pass complete").assertIsDisplayed()
        val original = holder
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Review pass complete").assertIsDisplayed()
        compose.runOnIdle { assertSame(original, holder); navigation.navigate("detail") }
        compose.onNodeWithText("Temporary detail").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Review pass complete").assertIsDisplayed()
        compose.runOnIdle { assertSame(original, holder) }
        capture("scoped-retained")
        compose.onNodeWithText("Review skipped").performClick()
        selected(1)
        compose.onNodeWithText("Skip for this review session").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Review game 1").performClick()
        selected(1)
        compose.runOnIdle { assertNotSame(original, holder); assertTrue(holder.session.state.value.deferredAppIds.isEmpty()) }
        compose.onNodeWithText("Use match").performScrollTo().performClick()
        compose.onNodeWithText("Open general review").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(2L, 3L), queue.value.map { it.appId }) }
    }

    @Test fun selectionAndDeferredIdsSurviveRecreationAndDetailReturn() {
        compose.onNodeWithText("Open general review").performClick()
        compose.onNodeWithText("Next").performClick()
        selected(2)
        val original = holder
        compose.activityRule.scenario.recreate()
        selected(2)
        compose.runOnIdle { assertSame(original, holder); navigation.navigate("detail") }
        Espresso.pressBack()
        selected(2)
        compose.runOnIdle { assertEquals(setOf(1L), holder.session.state.value.deferredAppIds) }
        capture("selection-retained")
    }

    @Test fun tabDepartureDismissesSessionBeforeSavingOtherDestinations() {
        compose.onNodeWithText("Open general review").performClick()
        compose.onNodeWithText("Next").performClick()
        val original = holder
        compose.runOnIdle { navigation.navigateToTopLevelDestination("library") }
        compose.onNodeWithText("Library tab").assertIsDisplayed()
        compose.runOnIdle { navigation.navigateToTopLevelDestination("home") }
        compose.onNodeWithText("Open general review").performClick()
        selected(1)
        compose.runOnIdle { assertNotSame(original, holder); assertTrue(holder.session.state.value.deferredAppIds.isEmpty()) }
    }

    /** Run in separate instrumentation processes, with an adb force-stop between save and restore. */
    @Test fun processRestartRestoresRouteWithoutSessionDeferrals() {
        assumeTrue(restartPhase != null)
        if (restartPhase == "save") {
            compose.onNodeWithText("Review game 1").performClick()
            compose.onNodeWithText("Skip for this review session").performClick()
            compose.onNodeWithText("Review pass complete").assertIsDisplayed()
            compose.runOnIdle {
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(navigation.saveState())
                    restartFile.writeBytes(parcel.marshall())
                } finally { parcel.recycle() }
            }
        } else {
            selected(1)
            compose.runOnIdle {
                assertEquals(1L, holder.session.state.value.scopedAppId)
                assertTrue(holder.session.state.value.deferredAppIds.isEmpty())
            }
            capture("process-restarted")
            assertTrue(restartFile.delete())
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "hltb-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
