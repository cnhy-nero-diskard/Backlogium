package com.example.backlogium.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.backlogium.domain.AchievementWatchPreferences
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w412dp-h915dp-mdpi")
class AchievementWatchSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun prerequisiteAndIndependentWatchCadenceAndAlertControlsAreAccessible() {
        var prefs by mutableStateOf(AchievementWatchPreferences())
        compose.setContent { BacklogiumTheme(darkTheme = true) {
            AchievementWatchSettings(prefs, false) { prefs = it }
        } }
        compose.onNodeWithText("Requires Live monitor. Achievement browsing is always available.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Achievement unlock alerts").performClick().assertIsOff()
        compose.onNodeWithContentDescription("Watch active-game achievements").assertIsOn()
        compose.onNodeWithText("30 seconds").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Watch active-game achievements").performClick().assertIsOff()
        compose.onRoot().captureRoboImage("achievement-watch/settings/dark-standard.png")
    }
}
