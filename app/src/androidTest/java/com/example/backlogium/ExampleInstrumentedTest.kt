package com.example.backlogium

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.backlogium.BuildConfig

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // The debug build installs under a `.debug` application-ID suffix, so resolve
        // against the generated APPLICATION_ID instead of a hardcoded package literal.
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }

    @Test
    fun appIdentityMatchesBuildVariant() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = appContext.packageManager
        val appInfo = packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        val label = packageManager.getApplicationLabel(appInfo).toString()
        val versionName = packageManager
            .getPackageInfo(BuildConfig.APPLICATION_ID, 0)
            .versionName

        if (BuildConfig.DEBUG) {
            // Debug builds present a distinct installed identity: debug label and a
            // `-debug` version-name suffix on top of the base release version.
            assertEquals("Backlogium Debug", label)
            assertEquals(BuildConfig.VERSION_NAME, versionName)
            assertTrue(
                "debug VERSION_NAME should carry the -debug suffix",
                BuildConfig.VERSION_NAME.endsWith("-debug")
            )
        } else {
            // Release identity stays unchanged.
            assertEquals("Backlogium", label)
            assertEquals(BuildConfig.VERSION_NAME, versionName)
        }
    }
}
