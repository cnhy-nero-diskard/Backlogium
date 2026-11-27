package com.example.backlogium.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.backlogium.data.notifications.ProgressCategory
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
class ProgressAlertSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun independentControlsAndAndroidActionsRemainAccessible() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        com.example.backlogium.data.notifications.ProgressAlertPlatform(context).registerChannels()
        var preferences by mutableStateOf(ProgressCategory.entries.associateWith { it.defaultEnabled })
        compose.setContent { BacklogiumTheme(darkTheme = true) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProgressAlertSettings(preferences) { category, enabled -> preferences = preferences + (category to enabled) }
            }
        } }
        compose.onNodeWithContentDescription("New owned games").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Family-shared games").assertIsOn()
        compose.onNodeWithContentDescription("Daily quota").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Broken streaks").performScrollTo().assertIsOff()
        compose.onAllNodesWithText("Sound and vibration settings").assertCountEquals(6)
        compose.onNodeWithText("Progress notifications").performScrollTo()
        compose.onRoot().captureRoboImage("progress-alerts/settings/dark-standard.png")
    }
}
