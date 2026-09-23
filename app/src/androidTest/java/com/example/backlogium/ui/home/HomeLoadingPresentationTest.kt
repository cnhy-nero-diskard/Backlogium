package com.example.backlogium.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Rule
import org.junit.Test

class HomeLoadingPresentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstLoad_usesBoundedPlaceholders() {
        composeRule.setContent {
            BacklogiumTheme { HomeLoadingContent() }
        }

        composeRule.onNodeWithTag(HOME_LOADING_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(HOME_LOADING_PLACEHOLDER_TAG).assertCountEquals(4)
    }

    @Test
    fun refreshStatus_isVisibleWithoutReplacingContent() {
        composeRule.setContent {
            BacklogiumTheme { HomeUpdatingIndicator() }
        }

        composeRule.onNodeWithTag(HOME_UPDATING_TAG).assertIsDisplayed()
    }
}
