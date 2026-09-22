package com.example.backlogium.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], qualifiers = "en-rUS")
class RoborazziSpikeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test
    fun darkThemeCardCanBeCaptured() {
        val mutationEnabled = System.getProperty("screenshotSpikeMutation") == "true"
        composeRule.setContent {
            BacklogiumTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Card(
                            colors = if (mutationEnabled) {
                                androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                )
                            } else {
                                androidx.compose.material3.CardDefaults.cardColors()
                            },
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text("Screenshot spike", style = MaterialTheme.typography.titleLarge)
                                Text("Deterministic card", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        composeRule.onRoot().captureRoboImage("spike/dark-standard/theme-card.png")
    }
}
