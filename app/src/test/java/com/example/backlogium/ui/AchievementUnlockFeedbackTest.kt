package com.example.backlogium.ui

import com.example.backlogium.ui.theme.BacklogiumTheme
import androidx.compose.ui.test.isDialog
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.domain.AchievementUnlockGroup
import com.example.backlogium.domain.AchievementUnlockItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w412dp-h915dp-mdpi")
class AchievementUnlockFeedbackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun groupOffersReadableNamesAndOneActionWithoutMotion() {
        var opens = 0
        val group = AchievementUnlockGroup("fixture", "fixture", 1, "Fixture game", 1,
            listOf(AchievementUnlockItem("one", "First", null, null, 1.0), AchievementUnlockItem("two", "Second", null, null, 2.0)))
        compose.setContent { BacklogiumTheme(darkTheme = true) { AchievementUnlockDialog(group, {}, { opens++ }) } }
        compose.onNodeWithText("Fixture game").assertIsDisplayed()
        compose.onNodeWithText("2 achievements unlocked: First, Second").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("achievement-watch/unlock-group/dark-standard.png")
        compose.onNodeWithText("View achievements").performClick()
        assertEquals(1, opens)
    }
}
