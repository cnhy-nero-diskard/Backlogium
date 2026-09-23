package com.example.backlogium.ui.home

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.R
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.ui.util.UiFormat
import java.time.LocalDate
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeLocalePresentationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val originalLocale = Locale.getDefault()

    @Before
    fun setGermanLocale() {
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun nonDefaultLocale_keepsDefaultEnglishCopy_andFormatsValuesLocally() {
        val germanConfiguration = Configuration(
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration,
        ).apply {
            setLocale(Locale.GERMANY)
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides germanConfiguration) {
                BacklogiumTheme {
                    Column {
                        HomeNextActionSurface(
                            action = HomeNextAction.ChooseGame,
                            onOpenGame = {},
                            onOpenCollection = {},
                            onOpenLibrary = {},
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.home_streak_days,
                                2,
                                UiFormat.count(2),
                            ),
                        )
                        Text(UiFormat.date(LocalDate.of(2026, 8, 30)))
                    }
                }
            }
        }

        composeRule.onNodeWithText("Choose a game from your Library.").assertIsDisplayed()
        composeRule.onNodeWithText("Browse Library").assertIsDisplayed()
        composeRule.onNodeWithText("2 days").assertIsDisplayed()
        composeRule.onNodeWithText("30.08.2026").assertIsDisplayed()
    }
}
