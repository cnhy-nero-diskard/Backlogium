package com.example.backlogium.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeNarrowLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nextActionAndCollectionActions_stayInsideNarrowViewport() {
        composeRule.setContent {
            BacklogiumTheme {
                Box(Modifier.width(240.dp)) {
                    Column {
                        HomeNextActionSurface(
                            action = HomeNextAction.ContinueCollection(
                                collectionId = 7L,
                                collectionName = "A very long collection name",
                                game = HomeNextGame(42L, "A very long game name"),
                            ),
                            onOpenGame = {},
                            onOpenCollection = {},
                            onOpenLibrary = {},
                        )
                        HomeCollectionsHeader(
                            onCreateCollection = {},
                            onOpenCollections = {},
                            onPlanGap = {},
                        )
                    }
                }
            }
        }

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        listOf(
            HOME_NEXT_ACTION_TAG,
            HOME_NEXT_ACTION_PRIMARY_TAG,
            HOME_NEXT_ACTION_COLLECTION_TAG,
            HOME_COLLECTIONS_NEW_TAG,
            HOME_COLLECTIONS_ACTIONS_TAG,
        ).forEach { tag ->
            val node = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue("$tag is clipped on the left", bounds.left >= root.left)
            assertTrue("$tag is clipped on the right", bounds.right <= root.right)
        }

        composeRule.onNodeWithTag(HOME_COLLECTIONS_ACTIONS_TAG).performClick()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_VIEW_ALL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_PLAN_GAP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_REORDER_TAG).assertDoesNotExist()
    }

    @Test
    fun emptyCollectionsAtNarrowWidth_keepActionsAttachedAndPlanGapOnOneLine() {
        composeRule.setContent {
            BacklogiumTheme {
                Box(Modifier.width(240.dp)) {
                    CollectionsSection(
                        cards = emptyList(),
                        onOpenCollection = {},
                        onCreateCollection = {},
                        onOpenCollections = {},
                        onPlanGap = {},
                        scrollState = rememberScrollState(),
                        scrollViewport = null,
                        onReorderCollections = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("No collections yet").assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_NEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_ACTIONS_TAG)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_VIEW_ALL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_PLAN_GAP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_REORDER_TAG).assertDoesNotExist()

        val newButton = composeRule.onNodeWithTag(HOME_COLLECTIONS_NEW_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val planGapItem = composeRule.onNodeWithTag(HOME_PLAN_GAP_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val planGapLabel = composeRule.onNodeWithText(
            "Plan a gap before a release",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Plan a gap has room beyond the old one-third action column",
            planGapItem.width >= newButton.width * 2f,
        )
        assertTrue(
            "Plan a gap stays on one line in the secondary menu",
            planGapLabel.height < planGapItem.height * 0.75f,
        )
    }
}
