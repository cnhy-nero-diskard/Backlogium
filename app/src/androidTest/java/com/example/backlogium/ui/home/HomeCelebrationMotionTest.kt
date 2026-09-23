package com.example.backlogium.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.example.backlogium.R
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeCelebrationMotionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reducedMotion_usesStaticEarnedTreatment_andAcknowledgesOnce() {
        var finished = 0

        composeRule.setContent {
            BacklogiumTheme {
                CelebrationAnimation(
                    resId = R.raw.levelup,
                    play = true,
                    onFinished = { finished++ },
                    staticLabel = "Level-up earned",
                    staticTag = HOME_LEVEL_UP_STATIC_TAG,
                    reducedMotionOverride = true,
                )
            }
        }

        composeRule.onNodeWithTag(HOME_LEVEL_UP_STATIC_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Level-up earned").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun ordinaryMotion_doesNotUseStaticTreatment() {
        composeRule.setContent {
            BacklogiumTheme {
                CelebrationAnimation(
                    resId = R.raw.levelup,
                    play = false,
                    onFinished = {},
                    staticLabel = "Level-up earned",
                    staticTag = HOME_LEVEL_UP_STATIC_TAG,
                    reducedMotionOverride = false,
                )
            }
        }

        composeRule.onAllNodesWithTag(HOME_LEVEL_UP_STATIC_TAG).assertCountEquals(0)
    }
}
