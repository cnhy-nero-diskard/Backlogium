package com.example.backlogium.ui.library

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryResourcePresentationTest {

    @Test
    fun libraryCopyFallsBackToDefaultEnglishWithLocaleAwareQuantities() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext
        val germanConfiguration = Configuration(application.resources.configuration).apply {
            setLocale(Locale.GERMANY)
        }
        val resources = application.createConfigurationContext(germanConfiguration).resources

        assertEquals(
            "1 game selected",
            resources.getQuantityString(R.plurals.library_selection_count, 1, 1),
        )
        assertEquals(
            "2 games selected",
            resources.getQuantityString(R.plurals.library_selection_count, 2, 2),
        )
        assertEquals(
            "No games match the active filters",
            resources.getString(R.string.library_no_matches_combined),
        )
    }
}
