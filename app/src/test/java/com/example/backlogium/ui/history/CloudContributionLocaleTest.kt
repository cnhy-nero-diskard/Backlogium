package com.example.backlogium.ui.history

import android.content.res.Configuration
import com.example.backlogium.R
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CloudContributionLocaleTest {
    @Test fun spanishContributionAndCadenceLabelsRemainDistinctWithoutIconsOrColor() {
        val configuration = Configuration(RuntimeEnvironment.getApplication().resources.configuration)
        configuration.setLocale(Locale.forLanguageTag("es-ES"))
        val resources = RuntimeEnvironment.getApplication()
            .createConfigurationContext(configuration).resources
        val recovered = resources.getString(R.string.history_cloud_recovered_partial)
        val timed = resources.getString(R.string.history_cloud_timed_partial)
        assertNotEquals(recovered, timed)
        assertTrue(recovered, recovered.contains("recuperado"))
        assertTrue(timed, timed.contains("Horario"))
        assertTrue(resources.getString(R.string.history_cloud_both_facts, recovered, timed)
            .contains(timed))
        assertTrue(resources.getString(R.string.settings_cloud_routine_48h).contains("48"))
        assertTrue(resources.getString(R.string.history_cloud_recovered_facts, 2).contains("2"))
    }
}
