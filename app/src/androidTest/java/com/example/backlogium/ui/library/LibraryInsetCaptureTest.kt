package com.example.backlogium.ui.library

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device evidence for the Library's final row with the shell's Scaffold and NavigationBar. */
@RunWith(AndroidJUnit4::class)
class LibraryInsetCaptureTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun captureFinalItemInEachDensity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "library-insets")
            .apply { mkdirs() }
        val density = mutableStateOf(GameListDensity.LIST)
        val barVisible = mutableStateOf(true)
        var scaffoldBottomPx = 0
        var systemBottomPx = 0
        composeRule.setContent {
            BacklogiumTheme {
                val localDensity = LocalDensity.current
                val navigationInsets = WindowInsets.navigationBars
                Scaffold(
                    bottomBar = {
                        AnimatedVisibility(
                            visible = barVisible.value,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        ) {
                            NavigationBar(Modifier.testTag("app-bottom-bar")) {
                                NavigationBarItem(
                                    selected = true,
                                    onClick = {},
                                    icon = { Text("L") },
                                    label = { Text("Library") },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    SideEffect {
                        scaffoldBottomPx = with(localDensity) {
                            innerPadding.calculateBottomPadding().roundToPx()
                        }
                        systemBottomPx = navigationInsets.getBottom(localDensity)
                    }
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        LibraryContent(
                            state = LibraryUiState(
                                loading = false,
                                configured = true,
                                libraryEmpty = false,
                                density = density.value,
                                backlog = (1..18).map { index ->
                                    BacklogGameUi(
                                        appId = index.toLong(),
                                        name = if (index == 18) "Fixture Final Game" else "Fixture Game $index",
                                        iconUrl = "",
                                        playtimeForever = index * 60,
                                    )
                                },
                            ),
                        )
                    }
                }
            }
        }
        val metrics = mutableListOf<String>()
        GameListDensity.entries.forEach { mode ->
            composeRule.runOnUiThread { density.value = mode }
            composeRule.waitForIdle()
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Fixture Final Game"))
            composeRule.waitForIdle()
            val barTop = composeRule.onNodeWithTag("app-bottom-bar")
                .getBoundsInRoot().top
            val finalTextBottom = composeRule.onNode(hasText("Fixture Final Game"))
                .getBoundsInRoot().bottom
            metrics += "${mode.name}: systemBottomPx=$systemBottomPx scaffoldBottomPx=$scaffoldBottomPx " +
                "barTop=$barTop finalTextBottom=$finalTextBottom"
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(output, "${mode.name.lowercase()}.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { barVisible.value = false }
        composeRule.mainClock.advanceTimeBy(150)
        composeRule.waitForIdle()
        metrics += "barExitMidpoint: systemBottomPx=$systemBottomPx scaffoldBottomPx=$scaffoldBottomPx"
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        metrics += "barExitComplete: systemBottomPx=$systemBottomPx scaffoldBottomPx=$scaffoldBottomPx"
        File(output, "measurements.txt").writeText(metrics.joinToString("\n"))
    }
}
