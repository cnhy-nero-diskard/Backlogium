package com.example.backlogium.ui.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext
import android.os.SystemClock
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule

internal const val NARROW_SCREEN_QUALIFIERS = "en-rUS-w320dp-h720dp-mdpi"
internal const val STANDARD_SCREEN_QUALIFIERS = "en-rUS-w412dp-h915dp-mdpi"
internal const val FIXED_SCREEN_TIME_MILLIS = 1_768_464_000_000L

/** NATIVE gradient blending varies by a few pixels across the macOS and Linux screenshot hosts. */
@OptIn(ExperimentalRoborazziApi::class)
internal fun SemanticsNodeInteraction.captureLibraryGradientScreenshot(path: String) {
    val options = provideRoborazziContext().options
    captureRoboImage(
        path,
        options.copy(
            compareOptions = options.compareOptions.copy(
                resultValidator = ThresholdValidator(LIBRARY_GRADIENT_DIFF_THRESHOLD),
            ),
        ),
    )
}

private const val LIBRARY_GRADIENT_DIFF_THRESHOLD = 0.0001f

internal data class MainScreenshotFixture(
    val kind: MainFixtureKind,
    val darkTheme: Boolean,
)

internal abstract class MainScreenshotTestBase {
    @get:Rule
    val composeRule = createAndroidComposeRule<ScreenshotTestActivity>()

    private lateinit var previousLocale: Locale
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun setUpDeterministicScreenshotEnvironment() {
        previousLocale = Locale.getDefault()
        previousTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        SystemClock.setCurrentTimeMillis(FIXED_SCREEN_TIME_MILLIS)

        // Capture the first deterministic frame; Lottie and infinite Compose transitions cannot tick.
        composeRule.mainClock.autoAdvance = false
    }

    protected fun captureFixture(
        path: String,
        kind: MainFixtureKind,
        darkTheme: Boolean,
        beforeCapture: () -> Unit = {},
    ) {
        val fixture = MainScreenshotFixture(kind = kind, darkTheme = darkTheme)
        composeRule.setContent {
            BacklogiumTheme(darkTheme = fixture.darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreenshotFixtureHost(fixture)
                }
            }
        }
        // Compose needs one frame to install the content; subsequent animation frames stay frozen.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        beforeCapture()
        val root = composeRule.onRoot()
        if (kind == MainFixtureKind.LIBRARY_POPULATED || kind == MainFixtureKind.LIBRARY_SELECTION) {
            root.captureLibraryGradientScreenshot(path)
        } else {
            root.captureRoboImage(path)
        }
    }

    @After
    fun restoreScreenshotEnvironment() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousTimeZone)
    }
}
