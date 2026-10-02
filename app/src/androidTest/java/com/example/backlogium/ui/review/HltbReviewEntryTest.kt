package com.example.backlogium.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.ui.library.BacklogGameUi
import com.example.backlogium.ui.library.LibraryContent
import com.example.backlogium.ui.library.LibraryContentActions
import com.example.backlogium.ui.library.LibraryUiState
import com.example.backlogium.ui.library.LibraryVisitState
import com.example.backlogium.ui.library.libraryScrollItems
import com.example.backlogium.ui.library.WishlistUiState
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.settings.SettingsActions
import com.example.backlogium.ui.settings.SettingsDetailScreen
import com.example.backlogium.ui.settings.SettingsGroup
import com.example.backlogium.ui.settings.SettingsUiState
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Library, Gameplay Settings, and reviewer content with controlled lookup outcomes. */
@RunWith(AndroidJUnit4::class)
class HltbReviewEntryTest {
    private val density = mutableStateOf(GameListDensity.LIST)
    private val queue = mutableStateOf(initialQueue())
    private val needsAttention = mutableStateOf<Long?>(null)
    private val visit = LibraryVisitState()
    private lateinit var navigation: NavHostController
    private lateinit var holder: ReviewRouteTestViewModel
    private var detailId: Long? = null
    private var lookupId: Long? = null

    private fun game(id: Long, ambiguous: Boolean = false) = MatchCenterGameUi(
        id, "Steam game $id", matchStatus = if (ambiguous) HltbMatchState.NEEDS_REVIEW else HltbMatchState.UNMATCHED,
        candidates = if (ambiguous) listOf(HltbCandidate(id * 10, "HLTB game $id")) else emptyList(),
    )
    private fun initialQueue() = listOf(game(1, true), game(2))

    private fun libraryState() = LibraryUiState(
        loading = false, libraryEmpty = false, density = density.value,
        matchCenterCount = queue.value.size, needsAttentionAppId = needsAttention.value,
        backlog = listOf(2L, 3L).map { id ->
            BacklogGameUi(
                id, "Steam game $id", "", playtimeForever = 0,
                hltbStatus = queue.value.find { it.appId == id }?.matchStatus ?: HltbMatchState.NOT_COVERED,
            )
        },
    )

    @Composable private fun Host() {
        val controller = rememberNavController()
        SideEffect { navigation = controller }
        BacklogiumTheme {
            NavHost(controller, startDestination = "home") {
                composable("home") {
                    Column {
                        Button(onClick = { controller.navigate("library") }) { Text("Open Library") }
                        Button(onClick = { controller.navigate("settings/gameplay") }) { Text("Open Gameplay") }
                    }
                }
                composable("library") {
                    LibraryContent(
                        state = libraryState(), wishlistState = WishlistUiState(), visit = visit,
                        actions = LibraryContentActions(
                            onOpenGameDetail = { detailId = it; controller.navigate("detail") },
                            onOpenReview = { id -> controller.navigate(if (id == null) "hltb_review" else "hltb_review?appId=$id") },
                            onRefreshGame = { id, _ ->
                                lookupId = id
                                queue.value = queue.value.filterNot { it.appId == id } + game(id)
                                needsAttention.value = id
                            },
                            onConsumeNeedsAttention = { needsAttention.value = null },
                        ),
                    )
                }
                composable("settings/gameplay") {
                    SettingsDetailScreen(
                        SettingsGroup.GAMEPLAY, SettingsUiState(loading = false), noopSettingsActions(),
                        onBack = { controller.popBackStack() },
                        onOpenReview = { controller.navigate("hltb_review") },
                    )
                }
                composable("hltb_review?appId={appId}", arguments = listOf(
                    navArgument("appId") { type = NavType.LongType; defaultValue = -1L },
                )) { entry ->
                    val routeHolder: ReviewRouteTestViewModel = viewModel()
                    val session by routeHolder.session.state.collectAsStateWithLifecycle()
                    val id = entry.arguments?.getLong("appId")?.takeIf { it >= 0 }
                    SideEffect { holder = routeHolder }
                    LaunchedEffect(id) { id?.let(routeHolder.session::selectGame) }
                    LaunchedEffect(queue.value) { routeHolder.session.updateQueue(queue.value) }
                    HltbReviewContent(
                        session.toUiState(), onDone = { controller.popBackStack() },
                        actions = HltbReviewActions(
                            onSkip = routeHolder.session::skip, onReviewSkipped = routeHolder.session::reviewSkipped,
                            onBroaderSearch = { appId, _ -> queue.value = queue.value.map { if (it.appId == appId) game(appId, true) else it } },
                            onResolve = { appId, _ -> queue.value = queue.value.filterNot { it.appId == appId } },
                        ),
                    )
                }
                composable("detail") { Text("Opened detail $detailId") }
            }
        }
    }

    init { ScreenshotTestActivity.recreationContent = { Host() } }
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    @After fun cleanup() { ScreenshotTestActivity.recreationContent = null }

    private fun noopSettingsActions() = SettingsActions(
        onSyncNow = {}, onReconcileNow = {}, onLiveMonitorEnabledChanged = {},
        onFieldChanged = { _, _ -> }, onQuestModeChanged = {}, onAdvancedExpandedChanged = {},
        onRequestSave = {}, onDiscardChanges = {}, onConfirmSave = {}, onDismissConfirmation = {},
        onImportHistory = {}, onResetHistoryImport = {}, onAutoSnapshotEnabledChanged = {},
        onSnapshotRetentionCountChanged = {}, onSnapshotIntervalHoursChanged = {},
        onExportBackup = {}, onImportBackup = {}, onRestoreSnapshot = {}, onDeleteSnapshot = {},
        onConfirmMismatchImport = {}, onDismissMismatchImport = {}, onDismissBackupMessage = {},
    )

    private fun openGameAction(id: Long) {
        scrollToGame(id)
        capture("${density.value.name}-library-action-$id")
        if (density.value == GameListDensity.LIST) {
            compose.onNode(hasContentDescription("Manage focus") and hasAnyAncestor(hasText("Steam game $id"))).performClick()
        } else {
            compose.onNodeWithContentDescription("Completion times for Steam game $id").performClick()
        }
    }

    private fun scrollToGame(id: Long) {
        val index = libraryScrollItems(libraryState(), WishlistUiState()).indexOfFirst { id in it.gameIds }
        assertTrue(index >= 0)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
    }

    private fun selected(id: Long) {
        compose.onNodeWithText("Steam game $id").assertIsDisplayed()
        compose.runOnIdle { assertEquals(id, holder.session.state.value.scopedAppId) }
    }

    @Test fun listAndBothGridsUseTheCorrectTargetForReviewLookupAndDetail() {
        for (mode in GameListDensity.entries) {
            compose.runOnIdle { density.value = mode; queue.value = initialQueue() }
            compose.onNodeWithText("Open Library").performClick()
            openGameAction(2)
            compose.onNodeWithText("Choose match").performClick()
            selected(2)
            capture("${mode.name}-targeted")
            compose.runOnIdle { assertNull(detailId) }
            compose.onNodeWithText("Skip for this review session").performClick()
            compose.onNodeWithText("Review pass complete").assertIsDisplayed()
            compose.onNodeWithText("Review skipped").performClick()
            selected(2)
            compose.onNodeWithText("Try broader search").performScrollTo().performClick()
            selected(2)
            compose.onNodeWithText("Use match").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals("library", navigation.currentDestination?.route)
                assertEquals(listOf(1L), queue.value.map { it.appId })
            }
            openGameAction(3)
            compose.onNodeWithText("Refresh HowLongToBeat").performClick()
            selected(3)
            compose.runOnIdle { assertEquals(3L, lookupId); assertNull(needsAttention.value) }
            Espresso.pressBack()
            scrollToGame(3)
            compose.onNodeWithText("Steam game 3").performClick()
            compose.onNodeWithText("Opened detail 3").assertIsDisplayed()
            Espresso.pressBack()
            Espresso.pressBack()
            compose.runOnIdle { detailId = null }
        }
    }

    @Test fun gameplayShortcutOpensGeneralReviewWithAndWithoutAttention() {
        for (hasAttention in listOf(true, false)) {
            compose.runOnIdle { queue.value = if (hasAttention) initialQueue() else emptyList() }
            compose.onNodeWithText("Open Gameplay").performClick()
            compose.onNodeWithText("Match Center").assertIsDisplayed()
            capture("settings-gameplay-$hasAttention")
            compose.onNodeWithText("Match Center").assertIsDisplayed().performClick()
            compose.runOnIdle { assertNull(holder.session.state.value.scopedAppId) }
            if (hasAttention) {
                compose.onNodeWithText("Steam game 1").assertIsDisplayed()
                compose.onNodeWithText("Skip for this review session").performClick()
                compose.onNodeWithText("Skip for this review session").performClick()
                compose.onNodeWithText("Review pass complete").assertIsDisplayed()
            } else {
                compose.onNodeWithText("Nothing to review or rescue").assertIsDisplayed()
            }
            capture("settings-attention-$hasAttention")
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Match Center").assertIsDisplayed()
            Espresso.pressBack()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "hltb-review").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
