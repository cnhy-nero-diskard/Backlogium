package com.example.backlogium.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeNextActionSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusAction_showsCompactSummaryAndOnePrimaryDestination() {
        var openedGame: Long? = null

        composeRule.setContent {
            BacklogiumTheme {
                HomeNextActionSurface(
                    action = HomeNextAction.ContinueFocus(HomeNextGame(42L, "Hades")),
                    onOpenGame = { openedGame = it },
                    onOpenCollection = {},
                    onOpenLibrary = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Next action").assertCountEquals(0)
        composeRule.onNodeWithText("Continue Focus").assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_NEXT_ACTION_PRIMARY_TAG).performClick()

        assertEquals(42L, openedGame)
    }

    @Test
    fun collectionAction_reachesNextGameAndCollectionDestinations() {
        var openedGame: Long? = null
        var openedCollection: Long? = null

        composeRule.setContent {
            BacklogiumTheme {
                HomeNextActionSurface(
                    action = HomeNextAction.ContinueCollection(
                        collectionId = 7L,
                        collectionName = "Weekend queue",
                        game = HomeNextGame(42L, "Hades"),
                    ),
                    onOpenGame = { openedGame = it },
                    onOpenCollection = { openedCollection = it },
                    onOpenLibrary = {},
                )
            }
        }

        composeRule.onNodeWithTag(HOME_NEXT_ACTION_PRIMARY_TAG).performClick()
        composeRule.onNodeWithTag(HOME_NEXT_ACTION_COLLECTION_TAG).performClick()

        assertEquals(42L, openedGame)
        assertEquals(7L, openedCollection)
    }

    @Test
    fun chooseGameAction_reachesLibraryDestination() {
        var openedLibrary = false

        composeRule.setContent {
            BacklogiumTheme {
                HomeNextActionSurface(
                    action = HomeNextAction.ChooseGame,
                    onOpenGame = {},
                    onOpenCollection = {},
                    onOpenLibrary = { openedLibrary = true },
                )
            }
        }

        composeRule.onNodeWithTag(HOME_NEXT_ACTION_PRIMARY_TAG).performClick()

        assertEquals(true, openedLibrary)
    }

    @Test
    fun playingState_hidesTheWholeNextActionSurface() {
        composeRule.setContent {
            BacklogiumTheme {
                HomeNextActionSlot(
                    isInGame = true,
                    action = HomeNextAction.ContinueCollection(
                        collectionId = 7L,
                        collectionName = "Weekend queue",
                        game = HomeNextGame(42L, "Hades"),
                    ),
                    onOpenGame = {},
                    onOpenCollection = {},
                    onOpenLibrary = {},
                )
            }
        }

        composeRule.onNodeWithTag(HOME_NEXT_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Hades").assertDoesNotExist()
        composeRule.onNodeWithText("Choose a game from your Library.").assertDoesNotExist()
    }

    @Test
    fun chooseGameFallback_isCompactAtNormalWidth() {
        composeRule.setContent {
            BacklogiumTheme {
                Box(Modifier.width(360.dp)) {
                    HomeNextActionSlot(
                        isInGame = false,
                        action = HomeNextAction.ChooseGame,
                        onOpenGame = {},
                        onOpenCollection = {},
                        onOpenLibrary = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Choose a game from your Library.").assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_NEXT_ACTION_PRIMARY_TAG).assertIsDisplayed()
        val height = composeRule.onNodeWithTag(HOME_NEXT_ACTION_TAG)
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Normal-width fallback should stay within a compact row",
            height <= with(composeRule.density) { 64.dp.toPx() },
        )
    }

    @Test
    fun chooseGameFallback_staysCompactAtNarrowWidth() {
        composeRule.setContent {
            BacklogiumTheme {
                Box(Modifier.width(240.dp)) {
                    HomeNextActionSlot(
                        isInGame = false,
                        action = HomeNextAction.ChooseGame,
                        onOpenGame = {},
                        onOpenCollection = {},
                        onOpenLibrary = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Choose a game from your Library.").assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_NEXT_ACTION_PRIMARY_TAG).assertIsDisplayed()
        val height = composeRule.onNodeWithTag(HOME_NEXT_ACTION_TAG)
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Narrow-width fallback should wrap within a compact card",
            height <= with(composeRule.density) { 84.dp.toPx() },
        )
    }
}
