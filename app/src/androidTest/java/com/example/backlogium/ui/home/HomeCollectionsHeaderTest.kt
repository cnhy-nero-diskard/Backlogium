package com.example.backlogium.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeCollectionsHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryAndSecondaryActions_keepAllCollectionDestinationsReachable() {
        var created = false
        var openedAll = false
        var plannedGap = false

        composeRule.setContent {
            BacklogiumTheme {
                HomeCollectionsHeader(
                    onCreateCollection = { created = true },
                    onOpenCollections = { openedAll = true },
                    onPlanGap = { plannedGap = true },
                )
            }
        }

        composeRule.onNodeWithTag(HOME_COLLECTIONS_NEW_TAG)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_ACTIONS_TAG).performClick()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_VIEW_ALL_TAG)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(HOME_COLLECTIONS_ACTIONS_TAG).performClick()
        composeRule.onNodeWithTag(HOME_PLAN_GAP_TAG)
            .assertIsDisplayed()
            .performClick()

        assertEquals(true, created)
        assertEquals(true, openedAll)
        assertEquals(true, plannedGap)
    }
}
