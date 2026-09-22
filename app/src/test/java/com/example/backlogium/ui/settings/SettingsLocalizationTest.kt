package com.example.backlogium.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.example.backlogium.R
import com.example.backlogium.ui.util.UiFormat
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsLocalizationTest {

    @Test
    fun settingsCopyFallsBackToDefaultResourcesForUnsupportedLocales() {
        val context: Context = RuntimeEnvironment.getApplication()
        val english = context.getString(R.string.settings_group_account_sync_title)
        val frenchConfiguration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.FRANCE))
        }

        val french = context
            .createConfigurationContext(frenchConfiguration)
            .getString(R.string.settings_group_account_sync_title)

        assertEquals("Account & sync", english)
        assertEquals(english, french)
    }

    @Test
    fun localeAwareFormattingStillChangesWithTheActiveLocale() {
        val originalLocale = Locale.getDefault()
        val timestamp = 1_753_478_400_000L
        try {
            Locale.setDefault(Locale.US)
            val usDate = UiFormat.dateTime(timestamp, ZoneId.of("UTC"))
            val usCount = UiFormat.count(1_234_567)

            Locale.setDefault(Locale.GERMANY)
            val germanDate = UiFormat.dateTime(timestamp, ZoneId.of("UTC"))
            val germanCount = UiFormat.count(1_234_567)

            assertNotEquals(usDate, germanDate)
            assertNotEquals(usCount, germanCount)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
