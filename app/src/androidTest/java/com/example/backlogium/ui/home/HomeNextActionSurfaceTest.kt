package com.example.backlogium.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeNextActionSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusAction_hasOneHeadingAndOnePrimaryDestination() {
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

        composeRule.onAllNodesWithText("Next action").assertCountEquals(1)
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
}
