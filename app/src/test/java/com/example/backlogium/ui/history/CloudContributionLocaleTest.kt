package com.example.backlogium.ui.history

import android.content.res.Configuration
import com.example.backlogium.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CloudContributionLocaleTest {
    @Test fun nonDefaultLocaleFallsBackToDistinctEnglishContributionLabels() {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag("es-ES"))
        val resources = application.createConfigurationContext(configuration).resources

        val recovered = resources.getString(R.string.history_cloud_recovered_partial)
        val timed = resources.getString(R.string.history_cloud_timed_partial)

        // The app-ui and app-settings requirements ship no locale-qualified resources: a
        // non-default locale falls back to the default English copy, while the recovered and
        // timing-informed facts stay distinguishable without relying on an icon or color.
        assertEquals("Partly recovered play", recovered)
        assertEquals("Partly cloud-informed timing", timed)
        assertNotEquals(recovered, timed)
        assertEquals(
            "Partly recovered play. Partly cloud-informed timing.",
            resources.getString(R.string.history_cloud_both_facts, recovered, timed),
        )
        assertEquals(
            "At least 48 hours apart",
            resources.getString(R.string.settings_cloud_routine_48h),
        )
        assertEquals(
            "Recovered play · 2 facts",
            resources.getString(R.string.history_cloud_recovered_facts, 2),
        )
    }
}
