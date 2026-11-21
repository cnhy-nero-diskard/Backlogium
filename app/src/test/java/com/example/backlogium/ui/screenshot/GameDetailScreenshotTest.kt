package com.example.backlogium.ui.screenshot

import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = NARROW_SCREEN_QUALIFIERS)
internal class GameDetailScreenshotNarrowTest : MainScreenshotTestBase() {
    @Test fun owned() = captureFixture("detail/owned/dark/narrow.png", MainFixtureKind.DETAIL_OWNED, true)
    @Test fun missing() = captureFixture("detail/missing/light/narrow.png", MainFixtureKind.DETAIL_MISSING, false) {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Choose cover").fetchSemanticsNodes().isNotEmpty() }
        composeRule.mainClock.autoAdvance = false
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = STANDARD_SCREEN_QUALIFIERS)
internal class GameDetailScreenshotStandardTest : MainScreenshotTestBase() {
    @Test fun owned() = captureFixture("detail/owned/light/standard.png", MainFixtureKind.DETAIL_OWNED, false)
    @Test fun shared() = captureFixture("detail/shared/dark/standard.png", MainFixtureKind.DETAIL_SHARED, true)
    @Test fun achievements() {
        captureFixture("detail/achievements/dark/standard.png", MainFixtureKind.DETAIL_OWNED, true) {
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithTag("game-detail-list").performScrollToNode(hasText("First steps"))
            composeRule.mainClock.autoAdvance = false
            composeRule.waitForIdle()
        }
    }
}
