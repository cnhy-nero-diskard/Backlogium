package com.example.backlogium.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.R
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focused coverage for the accessible selection semantics added in 3.3.
 *
 * Both the list row and the grid cell expose the same contract: a long-click label that names
 * the game (select outside selection mode, toggle inside it), a `selected` state, and a named
 * "Toggle selection" custom action that invokes the long-press handler. A resource-only test
 * stays green if any of those regress, so this composes the real cards and reads the merged
 * semantics back.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryGameSelectionSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listRowInOrdinaryModeExposesSelectLabelUnselectedStateAndToggleAction() {
        var toggles = 0
        composeRule.setContent {
            BacklogiumTheme {
                LibraryGameRow(
                    game = game(),
                    density = GameListDensity.LIST,
                    selected = false,
                    selectionMode = false,
                    onClick = {},
                    onLongClick = { toggles++ },
                    onManageGoal = {},
                )
            }
        }

        val card = composeRule.onNode(hasClickAction() and hasOnLongClick())
        card.assertIsNotSelected()

        val node = card.fetchSemanticsNode()
        assertEquals(selectLabel(), node.config[SemanticsActions.OnLongClick].label)
        assertEquals(false, node.config[SemanticsProperties.Selected])
        assertEquals(
            listOf(toggleActionLabel()),
            node.config[SemanticsActions.CustomActions].map { it.label },
        )

        composeRule.runOnUiThread {
            node.config[SemanticsActions.CustomActions].single().action()
        }
        composeRule.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test
    fun listRowInSelectionModeExposesToggleLabelSelectedStateAndToggleAction() {
        var toggles = 0
        composeRule.setContent {
            BacklogiumTheme {
                LibraryGameRow(
                    game = game(),
                    density = GameListDensity.LIST,
                    selected = true,
                    selectionMode = true,
                    onClick = {},
                    onLongClick = { toggles++ },
                    onManageGoal = {},
                )
            }
        }

        val card = composeRule.onNode(hasClickAction() and hasOnLongClick())
        card.assertIsSelected()

        val node = card.fetchSemanticsNode()
        assertEquals(toggleForGameLabel(), node.config[SemanticsActions.OnLongClick].label)
        assertEquals(true, node.config[SemanticsProperties.Selected])
        assertEquals(
            listOf(toggleActionLabel()),
            node.config[SemanticsActions.CustomActions].map { it.label },
        )

        composeRule.runOnUiThread {
            node.config[SemanticsActions.CustomActions].single().action()
        }
        composeRule.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test
    fun gridCellInOrdinaryModeExposesSelectLabelUnselectedStateAndToggleAction() {
        var toggles = 0
        composeRule.setContent {
            BacklogiumTheme {
                LibraryGameCell(
                    game = game(),
                    density = GameListDensity.GRID,
                    selected = false,
                    selectionMode = false,
                    onClick = {},
                    onLongClick = { toggles++ },
                )
            }
        }

        val card = composeRule.onNode(hasClickAction() and hasOnLongClick())
        card.assertIsNotSelected()

        val node = card.fetchSemanticsNode()
        assertEquals(selectLabel(), node.config[SemanticsActions.OnLongClick].label)
        assertEquals(false, node.config[SemanticsProperties.Selected])
        assertEquals(
            listOf(toggleActionLabel()),
            node.config[SemanticsActions.CustomActions].map { it.label },
        )

        composeRule.runOnUiThread {
            node.config[SemanticsActions.CustomActions].single().action()
        }
        composeRule.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test
    fun gridCellInSelectionModeExposesToggleLabelSelectedStateAndToggleAction() {
        var toggles = 0
        composeRule.setContent {
            BacklogiumTheme {
                LibraryGameCell(
                    game = game(),
                    density = GameListDensity.GRID,
                    selected = true,
                    selectionMode = true,
                    onClick = {},
                    onLongClick = { toggles++ },
                )
            }
        }

        val card = composeRule.onNode(hasClickAction() and hasOnLongClick())
        card.assertIsSelected()

        val node = card.fetchSemanticsNode()
        assertEquals(toggleForGameLabel(), node.config[SemanticsActions.OnLongClick].label)
        assertEquals(true, node.config[SemanticsProperties.Selected])
        assertEquals(
            listOf(toggleActionLabel()),
            node.config[SemanticsActions.CustomActions].map { it.label },
        )

        composeRule.runOnUiThread {
            node.config[SemanticsActions.CustomActions].single().action()
        }
        composeRule.waitForIdle()
        assertEquals(1, toggles)
    }

    private fun hasOnLongClick() = SemanticsMatcher("has OnLongClick") { node ->
        node.config.contains(SemanticsActions.OnLongClick)
    }

    private fun game() = LibraryDisplayGame(
        appId = 440L,
        name = GAME_NAME,
        iconUrl = "",
        headerUrl = "",
        heroCapsuleUrl = "",
        playtimeForever = 0,
        completionistMinutes = null,
        hltbStatus = HltbMatchState.NOT_COVERED,
        fetchOp = null,
        achievementUnlocked = null,
        achievementTotal = null,
        xpContributed = 0L,
        isCurrentlyPlaying = false,
    )

    private fun selectLabel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(R.string.library_select_game, GAME_NAME)
    }

    private fun toggleForGameLabel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(R.string.library_toggle_game_selection, GAME_NAME)
    }

    private fun toggleActionLabel(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getString(R.string.library_toggle_selection)
    }

    private companion object {
        const val GAME_NAME = "Team Fortress 2"
    }
}
