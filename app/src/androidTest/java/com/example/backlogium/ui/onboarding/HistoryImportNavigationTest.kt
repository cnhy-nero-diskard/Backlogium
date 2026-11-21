package com.example.backlogium.ui.onboarding

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.repo.HistoryImportGateway
import com.example.backlogium.data.repo.HistoryImportReadiness
import com.example.backlogium.ui.navigation.AppBottomNavigation
import com.example.backlogium.ui.navigation.Destination
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.FirstRunSetupGateway
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryImportNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun systemBackDuringPostCommitRecomputeCannotLeaveStaleXp() {
        val history = DelayedHistoryImport()
        val setup = FakeFirstRunSetup()
        val activeViewModel = AtomicReference<HistoryImportViewModel?>(null)
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(HistoryImportViewModel::class.java))
                return HistoryImportViewModel(history, setup) as T
            }
        }

        composeRule.setContent {
            BacklogiumTheme {
                val navController = rememberNavController()
                val entry by navController.currentBackStackEntryAsState()
                Scaffold(
                    bottomBar = {
                        AppBottomNavigation(
                            currentRoute = entry?.destination?.route,
                            onDestinationSelected = { navController.navigate(it.route) },
                            modifier = Modifier.testTag("app-bottom-bar"),
                        )
                    },
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = Destination.HOME.route,
                        modifier = Modifier.padding(padding),
                    ) {
                        composable(Destination.HOME.route) {
                            Column {
                                Text("Home destination")
                                Button(onClick = { navController.navigate("onboarding") }) {
                                    Text("Open onboarding")
                                }
                            }
                        }
                        composable(Destination.LIBRARY.route) { Text("Library destination") }
                        composable("onboarding") { backStackEntry ->
                            val viewModel = ViewModelProvider(backStackEntry, viewModelFactory)
                                .get(HistoryImportViewModel::class.java)
                            activeViewModel.set(viewModel)
                            HistoryImportStep(
                                onDone = { navController.popBackStack() },
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Open onboarding").performClick()
        composeRule.onNodeWithTag("app-bottom-bar").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            history.historyImportReadiness.value == HistoryImportReadiness.READY
        }
        composeRule.onNodeWithText("Import Steam history").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { history.pendingImportRecompute.get() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            activeViewModel.get()?.uiState?.value?.importing == true
        }
        composeRule.waitForIdle()
        assertTrue(activeViewModel.get()!!.uiState.value.importing)
        assertEquals(0L, history.totalXp.get())

        // Exercise Android's route Back dispatch while the raw import has committed but XP has not.
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent 4"),
        ).use { it.readBytes() }

        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "Count your pre-existing Steam playtime toward XP. This optional import credits each game's past hours once.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Home destination").assertDoesNotExist()
        composeRule.onNodeWithTag("app-bottom-bar").assertDoesNotExist()
        assertTrue(history.pendingImportRecompute.get())
        assertEquals(0L, history.totalXp.get())

        history.finishRecompute.complete(Unit)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            history.totalXp.get() == 125L && !history.pendingImportRecompute.get()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Home destination").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Home destination").assertIsDisplayed()
        assertFalse(setup.firstRunSetupActive.value)
        assertEquals(125L, history.totalXp.get())
        assertFalse(history.pendingImportRecompute.get())
    }

    private class DelayedHistoryImport : HistoryImportGateway {
        override val historyImportReadiness = MutableStateFlow(HistoryImportReadiness.READY)
        val pendingImportRecompute = AtomicBoolean(false)
        val totalXp = AtomicLong(0)
        val finishRecompute = CompletableDeferred<Unit>()

        override suspend fun importSteamHistory(): Boolean {
            pendingImportRecompute.set(true)
            finishRecompute.await()
            totalXp.set(125L)
            pendingImportRecompute.set(false)
            return true
        }
    }

    private class FakeFirstRunSetup : FirstRunSetupGateway {
        override val firstRunSetupActive = MutableStateFlow(true)
        override suspend fun claimFirstRunSetup() = Unit
        override suspend fun releaseFirstRunSetup() {
            firstRunSetupActive.value = false
        }
    }
}
