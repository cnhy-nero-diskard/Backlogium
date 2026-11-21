package com.example.backlogium.ui.screenshot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = NARROW_SCREEN_QUALIFIERS)
internal class MainScreenScreenshotNarrowTest : MainScreenshotTestBase() {
    @Test
    fun homePopulatedLightBaseline() {
        captureFixture("main/home/populated/light/narrow.png", MainFixtureKind.HOME_POPULATED, darkTheme = false)
    }

    @Test
    fun homePopulatedDarkBaseline() {
        captureFixture("main/home/populated/dark/narrow.png", MainFixtureKind.HOME_POPULATED, darkTheme = true)
    }

    @Test
    fun libraryPopulatedLightBaseline() {
        captureFixture("main/library/populated/light/narrow.png", MainFixtureKind.LIBRARY_POPULATED, darkTheme = false)
    }

    @Test
    fun libraryPopulatedDarkBaseline() {
        captureFixture("main/library/populated/dark/narrow.png", MainFixtureKind.LIBRARY_POPULATED, darkTheme = true)
    }

    @Test
    fun historyPopulatedLightBaseline() {
        captureFixture("main/history/populated/light/narrow.png", MainFixtureKind.HISTORY_POPULATED, darkTheme = false)
    }

    @Test
    fun historyPopulatedDarkBaseline() {
        captureFixture("main/history/populated/dark/narrow.png", MainFixtureKind.HISTORY_POPULATED, darkTheme = true)
    }

    @Test
    fun historyAllocationsSingleLightBaseline() {
        captureFixture("main/history/allocations-single/light/narrow.png", MainFixtureKind.HISTORY_ALLOCATION_SINGLE, darkTheme = false)
    }

    @Test
    fun historyAllocationsSingleDarkBaseline() {
        captureFixture("main/history/allocations-single/dark/narrow.png", MainFixtureKind.HISTORY_ALLOCATION_SINGLE, darkTheme = true)
    }

    @Test
    fun historyAllocationsMultiLightBaseline() {
        captureFixture("main/history/allocations-multi/light/narrow.png", MainFixtureKind.HISTORY_ALLOCATION_MULTI, darkTheme = false)
    }

    @Test
    fun historyAllocationsMultiDarkBaseline() {
        captureFixture("main/history/allocations-multi/dark/narrow.png", MainFixtureKind.HISTORY_ALLOCATION_MULTI, darkTheme = true)
    }

    @Test
    fun historyMixedLightBaseline() {
        captureFixture("main/history/mixed/light/narrow.png", MainFixtureKind.HISTORY_MIXED, darkTheme = false)
    }

    @Test
    fun historyMixedDarkBaseline() {
        captureFixture("main/history/mixed/dark/narrow.png", MainFixtureKind.HISTORY_MIXED, darkTheme = true)
    }

    @Test
    fun historyUnavailableLightNarrowBaseline() {
        captureFixture("main/history/unavailable/light/narrow.png", MainFixtureKind.HISTORY_UNAVAILABLE, darkTheme = false)
    }

    @Test
    fun historyUnavailableDarkNarrowBaseline() {
        captureFixture("main/history/unavailable/dark/narrow.png", MainFixtureKind.HISTORY_UNAVAILABLE, darkTheme = true)
    }

    @Test
    fun analyticsPopulatedLightBaseline() {
        captureFixture("main/analytics/populated/light/narrow.png", MainFixtureKind.ANALYTICS_POPULATED, darkTheme = false)
    }

    @Test
    fun analyticsPopulatedDarkBaseline() {
        captureFixture("main/analytics/populated/dark/narrow.png", MainFixtureKind.ANALYTICS_POPULATED, darkTheme = true)
    }

    @Test
    fun analyticsAllocationsOnlyLightBaseline() {
        captureFixture("main/analytics/allocations-only/light/narrow.png", MainFixtureKind.ANALYTICS_ALLOCATIONS_ONLY, darkTheme = false)
    }

    @Test
    fun analyticsAllocationsOnlyDarkBaseline() {
        captureFixture("main/analytics/allocations-only/dark/narrow.png", MainFixtureKind.ANALYTICS_ALLOCATIONS_ONLY, darkTheme = true)
    }

    @Test
    fun settingsOverviewLightBaseline() {
        captureFixture("main/settings/overview/light/narrow.png", MainFixtureKind.SETTINGS_OVERVIEW, darkTheme = false)
    }

    @Test
    fun settingsOverviewDarkBaseline() {
        captureFixture("main/settings/overview/dark/narrow.png", MainFixtureKind.SETTINGS_OVERVIEW, darkTheme = true)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = STANDARD_SCREEN_QUALIFIERS)
internal class MainScreenScreenshotStandardTest : MainScreenshotTestBase() {
    @Test
    fun homePopulatedLightBaseline() {
        captureFixture("main/home/populated/light/standard.png", MainFixtureKind.HOME_POPULATED, darkTheme = false)
    }

    @Test
    fun homePopulatedDarkBaseline() {
        captureFixture("main/home/populated/dark/standard.png", MainFixtureKind.HOME_POPULATED, darkTheme = true)
    }

    @Test
    fun libraryPopulatedLightBaseline() {
        captureFixture("main/library/populated/light/standard.png", MainFixtureKind.LIBRARY_POPULATED, darkTheme = false)
    }

    @Test
    fun libraryPopulatedDarkBaseline() {
        captureFixture("main/library/populated/dark/standard.png", MainFixtureKind.LIBRARY_POPULATED, darkTheme = true)
    }

    @Test
    fun historyPopulatedLightBaseline() {
        captureFixture("main/history/populated/light/standard.png", MainFixtureKind.HISTORY_POPULATED, darkTheme = false)
    }

    @Test
    fun historyPopulatedDarkBaseline() {
        captureFixture("main/history/populated/dark/standard.png", MainFixtureKind.HISTORY_POPULATED, darkTheme = true)
    }

    @Test
    fun historyAllocationsSingleLightBaseline() {
        captureFixture("main/history/allocations-single/light/standard.png", MainFixtureKind.HISTORY_ALLOCATION_SINGLE, darkTheme = false)
    }

    @Test
    fun historyAllocationsSingleDarkBaseline() {
        captureFixture("main/history/allocations-single/dark/standard.png", MainFixtureKind.HISTORY_ALLOCATION_SINGLE, darkTheme = true)
    }

    @Test
    fun historyAllocationsMultiLightBaseline() {
        captureFixture("main/history/allocations-multi/light/standard.png", MainFixtureKind.HISTORY_ALLOCATION_MULTI, darkTheme = false)
    }

    @Test
    fun historyAllocationsMultiDarkBaseline() {
        captureFixture("main/history/allocations-multi/dark/standard.png", MainFixtureKind.HISTORY_ALLOCATION_MULTI, darkTheme = true)
    }

    @Test
    fun historyMixedLightBaseline() {
        captureFixture("main/history/mixed/light/standard.png", MainFixtureKind.HISTORY_MIXED, darkTheme = false)
    }

    @Test
    fun historyMixedDarkBaseline() {
        captureFixture("main/history/mixed/dark/standard.png", MainFixtureKind.HISTORY_MIXED, darkTheme = true)
    }

    @Test
    fun analyticsPopulatedLightBaseline() {
        captureFixture("main/analytics/populated/light/standard.png", MainFixtureKind.ANALYTICS_POPULATED, darkTheme = false)
    }

    @Test
    fun analyticsPopulatedDarkBaseline() {
        captureFixture("main/analytics/populated/dark/standard.png", MainFixtureKind.ANALYTICS_POPULATED, darkTheme = true)
    }

    @Test
    fun analyticsAllocationsOnlyLightBaseline() {
        captureFixture("main/analytics/allocations-only/light/standard.png", MainFixtureKind.ANALYTICS_ALLOCATIONS_ONLY, darkTheme = false)
    }

    @Test
    fun analyticsAllocationsOnlyDarkBaseline() {
        captureFixture("main/analytics/allocations-only/dark/standard.png", MainFixtureKind.ANALYTICS_ALLOCATIONS_ONLY, darkTheme = true)
    }

    @Test
    fun settingsOverviewLightBaseline() {
        captureFixture("main/settings/overview/light/standard.png", MainFixtureKind.SETTINGS_OVERVIEW, darkTheme = false)
    }

    @Test
    fun settingsOverviewDarkBaseline() {
        captureFixture("main/settings/overview/dark/standard.png", MainFixtureKind.SETTINGS_OVERVIEW, darkTheme = true)
    }

    @Test
    fun historyUnavailableLightStandardBaseline() {
        captureFixture("main/history/unavailable/light/standard.png", MainFixtureKind.HISTORY_UNAVAILABLE, darkTheme = false)
    }

    @Test
    fun historyUnavailableDarkStandardBaseline() {
        captureFixture("main/history/unavailable/dark/standard.png", MainFixtureKind.HISTORY_UNAVAILABLE, darkTheme = true)
    }

    @Test
    fun homeNowPlayingDarkStandardBaseline() {
        captureFixture("alternatives/home/now-playing/dark/standard.png", MainFixtureKind.HOME_NOW_PLAYING, darkTheme = true)
    }

    @Test
    fun homeFirstLoadDarkStandardBaseline() {
        captureFixture("alternatives/home/first-load/dark/standard.png", MainFixtureKind.HOME_FIRST_LOAD, darkTheme = true)
    }

    @Test
    fun libraryCombinedFilterNoResultsDarkStandardBaseline() {
        captureFixture("alternatives/library/combined-filter-no-results/dark/standard.png", MainFixtureKind.LIBRARY_NO_RESULTS, darkTheme = true)
    }

    @Test
    fun librarySelectionDarkStandardBaseline() {
        captureFixture("alternatives/library/selection/dark/standard.png", MainFixtureKind.LIBRARY_SELECTION, darkTheme = true)
    }

    @Test
    fun historyEmptyDarkStandardBaseline() {
        captureFixture("alternatives/history/empty/dark/standard.png", MainFixtureKind.HISTORY_EMPTY, darkTheme = true)
        composeRule.onNodeWithText("Cloud activity").assertIsDisplayed()
    }

    @Test
    fun analyticsSelectedDayDarkStandardBaseline() {
        captureFixture("alternatives/analytics/selected-day/dark/standard.png", MainFixtureKind.ANALYTICS_SELECTED_DAY, darkTheme = true)
    }

    @Test
    fun analyticsEmptyWindowDarkStandardBaseline() {
        captureFixture("alternatives/analytics/empty-window/dark/standard.png", MainFixtureKind.ANALYTICS_EMPTY_WINDOW, darkTheme = true)
    }

    @Test
    fun settingsHealthyDarkStandardBaseline() {
        captureFixture("alternatives/settings/healthy/dark/standard.png", MainFixtureKind.SETTINGS_HEALTHY, darkTheme = true)
    }

    @Test
    fun settingsAttentionDarkStandardBaseline() {
        captureFixture("alternatives/settings/attention/dark/standard.png", MainFixtureKind.SETTINGS_ATTENTION, darkTheme = true)
    }
}
