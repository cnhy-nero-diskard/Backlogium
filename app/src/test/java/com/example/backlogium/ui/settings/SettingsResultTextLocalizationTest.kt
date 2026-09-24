package com.example.backlogium.ui.settings

import android.content.res.Configuration
import android.os.LocaleList
import com.example.backlogium.R
import com.example.backlogium.data.repo.CloudConfigurationResult
import com.example.backlogium.data.repo.ManualSharedGameImportResult
import com.example.backlogium.data.repo.PlayerDataProbe
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsResultTextLocalizationTest {
    @Test
    fun viewModelCloudResultResolvesFromResourcesUnderNonDefaultLocale() {
        val context = RuntimeEnvironment.getApplication()
        val frenchConfiguration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.FRANCE))
        }
        val frenchResources = context.createConfigurationContext(frenchConfiguration).resources
        val feedback = CloudConfigurationResult.Saved.toSettingsActionFeedback()

        assertEquals(R.string.settings_cloud_feedback_connected, feedback.message.resId)
        assertEquals("Cloud presence connected.", feedback.message.resolve(frenchResources))
    }

    @Test
    fun parameterizedViewModelResultUsesResourceFormattingAndPluralUnderNonDefaultLocale() {
        val context = RuntimeEnvironment.getApplication()
        val frenchConfiguration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.FRANCE))
        }
        val frenchResources = context.createConfigurationContext(frenchConfiguration).resources
        val feedback = manualImportFeedback(
            ManualSharedGameImportResult.Imported(
                620,
                "Portal 2",
                false,
                PlayerDataProbe.Returned(total = 1, unlocked = 1),
            ),
        )

        assertEquals(
            "Portal 2 is now tracked as Family Shared. Steam returned 1 achievement; 1 unlocked. " +
                "Borrowed playtime is observed by Backlogium, not supplied by Steam.",
            feedback.message.resolve(frenchResources),
        )
    }
}
